"""
NLS Self-Balancing Web v2.0 — 经络实时平衡仪 + Web仪表盘
启动: python balancer_web.py → 浏览器打开 http://localhost:8080
修复: loop异常处理 + 器官名称实时显示 + 改善/恶化详情
"""
import serial, struct, time, math, json, os, threading, traceback, random, winsound
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

COM_PORT = "COM4"
BASELINE_FILE = os.path.join(os.path.dirname(__file__), "nls_baseline.json")
PHASE_FILE = os.path.join(os.path.dirname(__file__), "nls_phase.json")

# 原版算法指纹（多用户, 从PCAP逆推, 换人→重新生成→重启即生效）
from algo_fingerprint import ALL_FINGERPRINTS, FINGERPRINT
PERSONS = list(ALL_FINGERPRINTS.keys())  # ["丽梦儿", "蔡明琦", ...]
CURRENT_PERSON = PERSONS[0] if PERSONS else "默认"  # 当前选中的人
B9_RANGE = range(14, 32)  # 实际抓包范围 14~31，对应 7.37MHz~20.4kHz

# ── Koldas 五大启发 ──
SCHUMANN_BASE = 7.83  # 地球舒曼基频 (Hz)
SCHUMANN_HARMONICS = [7.83, 14.3, 20.8, 27.3, 33.8]  # 舒曼谐波 (Hz)
PHI = 1.618033988749895  # 黄金比例

def schumann_coupling(responses, baseline):
    """
    耦合度指标: 测量18频段响应向量与舒曼谐波分形衍生模式的匹配度。
    返回 0~100 的耦合分数。
    原理: 耦合度 = 频段响应的有序程度 / 总能量
          有序 = 响应值与基线偏离的方向在相邻频段间一致
          无序 = 方向频繁反转 (噪声主导)
    """
    if len(responses) < 10:
        return 50
    diffs = []
    for b9 in sorted(responses.keys()):
        bl = baseline.get(b9, 105)
        d = responses[b9] - bl
        diffs.append(d)
    if not diffs:
        return 50
    # 计算方向一致性 (符号序列的秩序度)
    signs = [1 if d > 0 else (-1 if d < 0 else 0) for d in diffs]
    flips = sum(1 for i in range(1, len(signs)) if signs[i] != signs[i-1] and signs[i-1] != 0)
    max_flips = len(signs) - 1
    order = 1 - flips / max(max_flips, 1)
    # 振幅一致性: 偏差的标准差 vs 均值
    import statistics
    abs_diffs = [abs(d) for d in diffs]
    mean_abs = sum(abs_diffs) / len(abs_diffs) if abs_diffs else 1
    std_abs = statistics.stdev(abs_diffs) if len(abs_diffs) > 1 else 0
    cv = std_abs / mean_abs if mean_abs > 0 else 1
    # 综合: 高秩序 + 低变异 = 高耦合
    coupling = int(order * (1 - min(cv, 1)) * 100)
    return max(0, min(100, coupling + 20))  # +20 baseline offset

WUXING_CORRECTION = {"木": 1.0, "火": 1.5, "土": 1.0, "金": 1.2, "水": 0.8}

B9_MAP = {
    14: ("松果体/脑中枢", "7.37MHz", "火"), 15: ("下丘脑/内分泌", "5.21MHz", "火"),
    16: ("大脑皮层", "3.69MHz", "火"), 17: ("小脑/脑干", "2.61MHz", "火"),
    18: ("心脏/心血管", "1.84MHz", "火"), 19: ("心脏瓣膜", "1.30MHz", "火"),
    20: ("心脏后壁血管", "922kHz", "火"), 21: ("冠状动脉", "652kHz", "火"),
    22: ("肺/支气管", "461kHz", "金"), 23: ("肺实质/肺泡", "326kHz", "金"),
    24: ("甲状腺/甲状旁腺", "230kHz", "火"), 25: ("肝脏/肝血管", "163kHz", "木"),
    26: ("胃/食道", "115kHz", "土"), 27: ("十二指肠/小肠", "81.5kHz", "火"),
    28: ("胰腺/脾脏", "57.6kHz", "土"), 29: ("肾脏/肾上腺(参考)", "40.7kHz", "水"),
    30: ("大肠/直肠", "28.8kHz", "金"), 31: ("骨骼/关节/牙齿", "20.4kHz", "水"),
}


# ========== 阴阳数列谐波疗愈 ==========
# 太阳数列 (CH1): 2^n = 1,2,4,8,16,32
# 太阴数列 (CH2): Fibonacci = 1,1,2,3,5,8,13,21,34
SUN_SEQ = [1, 2, 4, 8, 16, 32]
MOON_SEQ = [1, 1, 2, 3, 5, 8, 13, 21, 34]

def _nearest(b9, seq):
    """返回 b9 到数列最近项的距离"""
    return min(abs(b9 - s) for s in seq)

# 预计算 18 个 b9 的太阳/太阴权重
YINYANG_WEIGHT = {}
for _b9 in B9_RANGE:
    d_sun = _nearest(_b9, SUN_SEQ)
    d_moon = _nearest(_b9, MOON_SEQ)
    w_sun = 1.0 / (1 + d_sun)
    w_moon = 1.0 / (1 + d_moon)
    total = w_sun + w_moon
    YINYANG_WEIGHT[_b9] = {
        "sun": round(w_sun / total, 2),
        "moon": round(w_moon / total, 2),
        "sun_b9": min(SUN_SEQ, key=lambda s: abs(_b9 - s)),
        "moon_b9": min(MOON_SEQ, key=lambda s: abs(_b9 - s)),
    }


class Balancer:
    def __init__(self):
        self.ser = None
        self.baseline = {}
        self.running = False
        self.calibrating = False
        self._algo_queue = []
        self._batch_num = 0
        self._pending_start = False  # 校准完成前点了开始→排队标记
        self.scan_amp = 20  # 扫描振幅 (3-80), 默认20比原15更敏感
        self.per_b9_amp = {}  # {b9: amp} 每个频段的独立最优振幅 (auto_tune填充)
        self.treat_speed = 1.0  # 治疗时间周期倍数 1~12
        self.min_interval = 0.49  # 最小命令间隔 (0.1~1s, 默认0.49s=PCAP实测)
        self.pause_threshold = 1.0  # 长暂停阈值 (0.1~6s, 默认1s)
        self.max_burst = 8  # 最长连续同频burst (1~12, 默认8)
        self.power_boost = 0  # 全局功率偏移 (+0~+5)
        # 每次启动清空旧基线，强制重新悬空校准
        if os.path.exists(BASELINE_FILE):
            os.remove(BASELINE_FILE)
        self.status = {
            "playing": False, "scanning": False, "round": 0,
            "deltas": {}, "verify": {}, "log": [],
            "improved": 0, "worsened": 0, "connected": False,
            "has_baseline": False,
            "scan_amp": self.scan_amp,
            "min_interval": self.min_interval,
            "pause_threshold": self.pause_threshold,
            "max_burst": self.max_burst,
            "power_boost": self.power_boost,
            "per_b9_amp": self.per_b9_amp,
            "algo": "ab",
            "ab_original": {"imp": 0, "wors": 0, "rounds": 0},
            "ab_legacy": {"imp": 0, "wors": 0, "rounds": 0},
            "ab_yinyang": {"imp": 0, "wors": 0, "rounds": 0},
            "ab_fusion": {"imp": 0, "wors": 0, "rounds": 0},
            "ab_schumann": {"imp": 0, "wors": 0, "rounds": 0},
            "ab_water": {"imp": 0, "wors": 0, "rounds": 0},
            "ab_jellium": {"imp": 0, "wors": 0, "rounds": 0},
            "ab_multiharm": {"imp": 0, "wors": 0, "rounds": 0},
            "coupling": 50, "spectral_entropy": 0.5,
            "ridges": {},  # 频谱脊线数据 {b9: amplitude}
        }
        self._lock = threading.Lock()
        self._snapshot_cache = {"deltas": [], "wuxing": [], "ridge": {}, "time": ""}
        self.phase_calib = {}
        self.load_baseline()
        if os.path.exists(PHASE_FILE):
            with open(PHASE_FILE) as f:
                self.phase_calib = json.load(f)

    def load_baseline(self):
        if os.path.exists(BASELINE_FILE):
            with open(BASELINE_FILE) as f:
                self.baseline = {int(k): v for k, v in json.load(f).items()}
            self.status["has_baseline"] = len(self.baseline) >= 18

    def connect(self):
        try:
            self.ser = serial.Serial(COM_PORT, 115200, timeout=0.5, write_timeout=0.5)
            self.status["connected"] = True
            return True
        except:
            self.status["connected"] = False
            return False

    def add_log(self, msg):
        with self._lock:
            self.status["log"].insert(0, msg)
            if len(self.status["log"]) > 50:
                self.status["log"].pop()

    def probe(self, b9, b11=15):
        if not self.ser or not self.ser.is_open:
            return [100] * 256
        try:
            cmd = bytearray(128)
            cmd[9] = b9; cmd[11] = b11; cmd[13] = b9; cmd[15] = b11
            self.ser.reset_input_buffer()
            if not self._safe_write(cmd):
                self.add_log("⚠ 写入失败，设备可能断开")
                return [100] * 256
            time.sleep(0.08)
            return list(self.ser.read(256))
        except (serial.SerialException, OSError) as e:
            self.status["connected"] = False
            if self.ser:
                try: self.ser.close()
                except: pass
                self.ser = None
            return [100] * 256

    def _safe_write(self, cmd):
        """安全写命令，自动应用功率偏移"""
        try:
            if self.ser and self.ser.is_open:
                if self.power_boost > 0:
                    cmd[11] = min(172, cmd[11] + self.power_boost)
                    cmd[15] = min(172, cmd[15] + self.power_boost)
                self.ser.write(bytes(cmd))
                return True
        except (serial.SerialException, OSError):
            self.status["connected"] = False
            if self.ser:
                try: self.ser.close()
                except: pass
                self.ser = None
        return False

    # ── 音频反馈 (432Hz调律, 仿AudioTone) ──
    @staticmethod
    def _b9tohz(b9):
        """b9→可听频率: 7.3728MHz逐半频→27~6912Hz(8个八度)"""
        hz = 7372800.0 / 2**((b9 - 14) / 2)
        while hz > 6912: hz /= 2
        while hz < 27: hz *= 2
        return int(hz)

    def _audio_beep(self, ch1_b9, ch2_b9, ms=80):
        """Windows蜂鸣: CH1短音+CH2短音, 形成双音差拍辨识"""
        try:
            hz1 = self._b9tohz(ch1_b9)
            hz2 = self._b9tohz(ch2_b9)
            if 37 <= hz1 <= 32767:
                winsound.Beep(int(hz1), int(ms))
                time.sleep(0.04)
                if 37 <= hz2 <= 32767 and abs(hz2 - hz1) > 10:
                    winsound.Beep(int(hz2), int(ms * 0.6))
        except: pass

    def scan(self, update_coupling=True):
        """扫描全部18频段，实时更新UI"""
        deltas = {}
        raw_vals = {}  # 用于耦合度计算
        for b9 in B9_RANGE:
            amp = self.per_b9_amp.get(b9, self.scan_amp)  # per-b9优先
            resp = self.probe(b9, amp)
            if len(resp) == 256:
                avg = sum(resp) / 256
                bl = self.baseline.get(b9, 105)
                d = round(avg - bl, 1)
                deltas[b9] = d
                raw_vals[b9] = avg
                organ, _, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
                freq = round(7.3728 / (2 ** ((b9 - 14) / 2)) * 1000)  # kHz
                yy = YINYANG_WEIGHT.get(b9, {"sun": 0.5, "moon": 0.5, "sun_b9": b9, "moon_b9": b9})
                with self._lock:
                    self.status["deltas"][str(b9)] = {
                        "b9": b9, "organ": organ, "wuxing": wuxing,
                        "freq": int(freq), "delta": d, "verified": None,
                        "sun": yy["sun"], "moon": yy["moon"],
                        "sun_b9": yy["sun_b9"], "moon_b9": yy["moon_b9"],
                    }
            time.sleep(0.02)
        # === 后处理: 耦合度+熵+Cole-Coleγ极+非线性NI+自注意力权重 ===
        if update_coupling and raw_vals:
            coupling = schumann_coupling(raw_vals, self.baseline)
            # ① 频谱熵
            vals = [v for v in raw_vals.values() if v > 0]
            if len(vals) > 3:
                total = sum(vals)
                probs = [v/total for v in vals]
                spec_entropy = -sum(p * math.log2(p+1e-12) for p in probs) / math.log2(len(vals))
            else:
                spec_entropy = 0.5
            # ② Cole-Cole γ极Δε proxy: 最大raw_val对应的b9 ≈ 弛豫峰
            peak_b9 = max(raw_vals, key=raw_vals.get)
            peak_freq = round(7.3728 / (2 ** ((peak_b9 - 14) / 2)) * 1000)  # kHz
            baseline_avg = sum(self.baseline.values()) / max(1, len(self.baseline))
            delta_eps = round(raw_vals[peak_b9] / max(1, baseline_avg) - 1, 3)  # normalized ε' boost
            tau_est = round(1.0 / max(1e6, peak_freq * 2e6 * math.pi), 9)  # ~ps scale
            # ③ 功率非线性NI(f) — 测3个代表性频段 (低/中/高)
            ni_map = {}
            for probe_b9 in [5, 17, 29]:
                self.ser.reset_input_buffer()
                cmd_lo = bytearray(128); cmd_lo[9]=probe_b9; cmd_lo[11]=10; cmd_lo[13]=probe_b9; cmd_lo[15]=10
                self._safe_write(cmd_lo); time.sleep(0.08)
                r_lo = list(self.ser.read(256)) if self.ser else [100]*256
                cmd_hi = bytearray(128); cmd_hi[9]=probe_b9; cmd_hi[11]=40; cmd_hi[13]=probe_b9; cmd_hi[15]=40
                self.ser.reset_input_buffer()
                self._safe_write(cmd_hi); time.sleep(0.08)
                r_hi = list(self.ser.read(256)) if self.ser else [100]*256
                if len(r_lo)==256 and len(r_hi)==256:
                    a_lo = sum(r_lo)/256; a_hi = sum(r_hi)/256
                    ni = round(abs(a_hi - a_lo) / max(1, a_lo), 3)
                    ni_map[str(probe_b9)] = {"ni": ni, "a_lo": a_lo, "a_hi": a_hi}
            avg_ni = round(sum(v["ni"] for v in ni_map.values()) / max(1, len(ni_map)), 3)
            # ④ 频点自注意力权重: delta大的频点权重大
            max_d = max(abs(d) for d in deltas.values()) if deltas else 1
            attn_weights = {str(b9): round(abs(d)/max_d, 2) for b9, d in sorted(deltas.items(), key=lambda x: -abs(x[1]))[:5]}
            with self._lock:
                self.status["coupling"] = coupling
                self.status["spectral_entropy"] = round(spec_entropy, 3)
                self.status["delta_eps"] = delta_eps
                self.status["tau_est"] = tau_est
                self.status["nonlinear_ni"] = round(avg_ni, 3)
                self.status["attn_weights"] = attn_weights
                self.status["ridges"] = {str(k): v for k, v in raw_vals.items()}
                # 缓存
                wux, wcnt = {}, {}
                for b9, d in deltas.items():
                    wu = B9_MAP.get(b9, ('?', '?', '土'))[2]
                    wux[wu] = wux.get(wu, 0) + abs(d)
                    wcnt[wu] = wcnt.get(wu, 0) + 1
                self._snapshot_cache = {
                    "deltas": [{k: v for k, v in self.status["deltas"].get(str(b9), {}).items()} for b9 in sorted(deltas.keys())],
                    "wuxing": [{"element": e, "sum": wux.get(e, 0), "count": wcnt.get(e, 0)} for e in ["土","金","水","木","火"]],
                    "ridge": {str(k): v for k, v in raw_vals.items()},
                    "spectral_entropy": spec_entropy, "delta_eps": delta_eps, "tau_est": tau_est,
                    "nonlinear_ni": avg_ni, "attn_weights": attn_weights,
                    "time": time.strftime('%Y-%m-%d %H:%M:%S'),
                }
        return deltas

    def balance_legacy(self, deltas):
        """异频反相: CH1≠CH2, 反相振幅, beat=|f1-f2|增加有效调制深度"""
        balanced = []
        for b9, delta in sorted(deltas.items(), key=lambda x: -abs(x[1])):
            if abs(delta) < 4: continue
            organ, _, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
            corr = WUXING_CORRECTION.get(wuxing, 1.0)
            adjust = min(int(abs(delta) * 0.5 * corr), 60)
            ph = self.phase_calib.get(str(b9), {})
            ph_boost = 0.6 if ph.get("grade") in ("强相消", "中相消") else 1.0
            adjust = int(adjust * ph_boost)
            b11 = max(3, 15 - adjust) if delta > 0 else min(172, 15 + adjust)
            b15 = min(172, 15 + adjust) if delta > 0 else max(3, 15 - adjust)
            # CH2频率偏移: 偏差越大偏移越大 b9±1~3
            offset = min(3, max(1, int(abs(delta) // 4)))
            ch2_b9 = max(14, min(31, b9 + offset if delta > 0 else b9 - offset))
            if self.ser and self.ser.is_open:
                cmd = bytearray(128)
                cmd[9] = b9; cmd[11] = b11; cmd[13] = ch2_b9; cmd[15] = b15
                self._safe_write(cmd)
            balanced.append({"b9": b9, "organ": organ, "delta": delta,
                "direction": "↓" if delta > 0 else "↑",
                "ch1b9": b9, "ch1amp": b11, "ch2b9": ch2_b9, "ch2amp": b15})
            time.sleep(0.08)
        return balanced

    def balance_original_hybrid(self, deltas, person=None):
        """原版NLS逼近 — 从 algo_fingerprint.py 读取12维指纹, person=指定人名"""
        fp = ALL_FINGERPRINTS.get(person or CURRENT_PERSON, FINGERPRINT)
        same_pct = fp.get("same_pct", 50)
        off_lo, off_hi = fp.get("offset_lo", -15), fp.get("offset_hi", 17)
        ch1_lo, ch1_hi = fp.get("ch1_lo", 3), fp.get("ch1_hi", 172)
        ch2_lo, ch2_hi = fp.get("ch2_lo", 3), fp.get("ch2_hi", 172)
        ch2_ratio = fp.get("ch2_ratio", 3.0)
        ch1_by_b9 = fp.get("ch1_per_b9", {})
        ch1_baseline = fp.get("ch1_baseline", 72)  # 全局CH1均值

        balanced = []
        for b9, delta in sorted(deltas.items(), key=lambda x: -abs(x[1])):
            if abs(delta) < 4:
                continue
            organ, _, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
            corr = WUXING_CORRECTION.get(wuxing, 1.0)

            # 按器官查表调振幅（12维指纹第4维: ch1_per_b9）
            b9_amp = ch1_by_b9.get(str(b9), ch1_by_b9.get(b9, ch1_baseline))
            amp_factor = b9_amp / max(1, ch1_baseline)  # >1=超均值推力, <1=降功率
            adjust = min(int(abs(delta) * 0.5 * corr * amp_factor), 60)

            # 振幅地基: 反相, CH1边界用PCAP实测范围
            b11 = max(ch1_lo, 15 - adjust) if delta > 0 else min(ch1_hi, 15 + adjust)
            b15 = min(ch1_hi, 15 + adjust) if delta > 0 else max(ch1_lo, 15 - adjust)

            # 同频/异频: 用b9 hash模拟PCAP实测比例
            is_same = (abs(hash(str(b9))) % 100) < same_pct

            if is_same:
                ch1_b9, ch2_b9 = b9, b9
            else:
                offset = abs(hash(str(b9) + str(delta))) % (off_hi - off_lo + 1) + off_lo
                ch1_b9, ch2_b9 = b9, min(31, b9 + offset)
                # CH2低幅: 复刻PCAP降比
                b15 = max(ch2_lo, int(b15 / ch2_ratio))

            if self.ser and self.ser.is_open:
                cmd = bytearray(128)
                cmd[9] = ch1_b9; cmd[11] = b11
                cmd[13] = ch2_b9; cmd[15] = b15
                self._safe_write(cmd)

            mode_tag = "同频" if is_same else "异频"
            balanced.append({
                "b9": b9, "organ": organ, "delta": delta,
                "direction": "↓" if delta > 0 else "↑",
                "mode": mode_tag,
                "ch1b9": ch1_b9, "ch1amp": b11, "ch2b9": ch2_b9, "ch2amp": b15,
                "amp_boost": round(amp_factor, 2),
            })
            time.sleep(0.08)
        return balanced

    def balance(self, deltas):
        """阴阳数列真谐波双频: 每异常器官1条命令, CH1=真实2^n CH2=真实Fibonacci
        全幅谐波频率 + 对称反相振幅, 与融合(同频+振幅调制)互补"""
        balanced = []
        for b9, delta in sorted(deltas.items(), key=lambda x: -abs(x[1])):
            if abs(delta) < 4:
                continue
            organ, _, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
            corr = WUXING_CORRECTION.get(wuxing, 1.0)
            adjust = min(int(abs(delta) * 0.5 * corr), 60)

            # 真实谐波频率: 不钳位, 使用完整 2^n 和 Fibonacci
            yy = YINYANG_WEIGHT.get(b9, {"sun": 0.5, "moon": 0.5, "sun_b9": b9, "moon_b9": b9})
            sun_b9 = yy["sun_b9"]
            moon_b9 = yy["moon_b9"]

            # 对称反相振幅
            sun_amp = max(3, 15 - adjust) if delta > 0 else min(172, 15 + adjust)
            moon_amp = min(172, 15 + adjust) if delta > 0 else max(3, 15 - adjust)

            if self.ser and self.ser.is_open:
                cmd = bytearray(128)
                cmd[9] = sun_b9; cmd[11] = sun_amp
                cmd[13] = moon_b9; cmd[15] = moon_amp
                self._safe_write(cmd)

            balanced.append({
                "b9": b9, "organ": organ, "delta": delta,
                "direction": "↓" if delta > 0 else "↑",
                "ch1b9": sun_b9, "ch1amp": sun_amp, "ch2b9": moon_b9, "ch2amp": moon_amp,
            })
            time.sleep(0.08)
        return balanced

    def balance_fusion(self, deltas):
        """融合算法: 同频反相地基 + 太阳/太阴振幅权重调制
        CH1/CH2频率相同(相干地基), 但振幅按阴阳数列权重不对称分配
        太阳主导的器官→CH1推力更强, 太阴主导→CH2拉力更强"""
        balanced = []
        for b9, delta in sorted(deltas.items(), key=lambda x: -abs(x[1])):
            if abs(delta) < 4:
                continue
            organ, _, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
            corr = WUXING_CORRECTION.get(wuxing, 1.0)
            adjust = min(int(abs(delta) * 0.5 * corr), 60)

            # 阴阳权重: 太阳(CH1推力) 太阴(CH2拉力)
            yy = YINYANG_WEIGHT.get(b9, {"sun": 0.5, "moon": 0.5, "sun_b9": b9, "moon_b9": b9})
            w_sun = yy["sun"]
            w_moon = yy["moon"]

            # 相位补偿: 强相消频段降低振幅(同legacy)
            ph = self.phase_calib.get(str(b9), {})
            ph_boost = 0.6 if ph.get("grade") in ("强相消", "中相消") else 1.0
            adjust = int(adjust * ph_boost)

            # 同频地基: CH1=CH2=b9 (相干反相)
            # 振幅按权重不对称分配: 太阳主导→CH1推力大, 太阴主导→CH2拉力大
            b11 = max(3, int(15 - adjust * w_sun)) if delta > 0 else min(172, int(15 + adjust * w_sun))
            b15 = min(172, int(15 + adjust * w_moon)) if delta > 0 else max(3, int(15 - adjust * w_moon))

            # CH2偏移: 微异频, 创造差拍增强调制
            c2 = max(14, min(31, b9 + (1 if delta > 0 else -1)))

            if self.ser and self.ser.is_open:
                cmd = bytearray(128)
                cmd[9] = b9; cmd[11] = b11
                cmd[13] = c2; cmd[15] = b15
                self._safe_write(cmd)

            balanced.append({
                "b9": b9, "organ": organ, "delta": delta,
                "direction": "↓" if delta > 0 else "↑",
                "ch1b9": b9, "ch1amp": b11, "ch2b9": c2, "ch2amp": b15,
            })
            time.sleep(0.08)
        return balanced

    def balance_schumann(self, deltas):
        """舒曼锚定: 对所有异常器官发射低频谐波重置生物节律锚点"""
        balanced = []
        for b9, delta in sorted(deltas.items(), key=lambda x: -abs(x[1])):
            if abs(delta) < 4: continue
            organ, *_ = B9_MAP.get(b9, ('?', '?', '土'))
            s_idx = min(4, int(abs(delta) / 5))
            sch_b9 = max(1, min(10, b9 - 8))
            weak_amp, ch2_b9 = 15, max(1, min(10, sch_b9 + s_idx + 1))
            if self.ser and self.ser.is_open:
                cmd = bytearray(128)
                cmd[9] = sch_b9; cmd[11] = weak_amp
                cmd[13] = ch2_b9; cmd[15] = weak_amp
                self._safe_write(cmd)
            balanced.append({"b9": b9, "organ": organ, "delta": delta, "direction": "⚓",
                "ch1b9": sch_b9, "ch1amp": 15, "ch2b9": ch2_b9, "ch2amp": 15})
            time.sleep(0.08)
        return balanced

    def calibrate_phase(self):
        """全频段相位校准: 扫14-31, 计算每个b9的干涉比
        用于Mode 2精确补偿: 干涉比<0.7=强相消(可用), >0.95=无相位效应(退化)"""
        if not self.ser or not self.ser.is_open:
            return {"error": "手环未连接"}
        self.add_log("📡 相位校准中…(扫18频段)")
        phase_map = {}
        for b9 in B9_RANGE:
            def tp(ch1_amp, ch2_amp):
                for _ in range(3):
                    cmd = bytearray(128)
                    cmd[9]=b9; cmd[11]=ch1_amp; cmd[13]=b9; cmd[15]=ch2_amp
                    self.ser.reset_input_buffer()
                    self._safe_write(cmd); time.sleep(0.12)
                    r = list(self.ser.read(256)) if self.ser else [100]*256
                    if len(r) == 256: return r
                return r
            r_ch1 = tp(15, 3)
            r_both = tp(15, 15)
            r_ch2 = tp(3, 15)
            if len(r_ch1)==256 and len(r_both)==256 and len(r_ch2)==256:
                a1 = sum(r_ch1)/256; a2 = sum(r_both)/256; a3 = sum(r_ch2)/256
                interfer = round(a2/max(1, a1+a3), 3)
                # 相消等级: <0.7强 / 0.7-1.0弱 / >1.0反常
                if interfer < 0.5: grade = "强相消"
                elif interfer < 0.85: grade = "中相消"
                elif interfer < 1.0: grade = "弱相消"
                else: grade = "无/反相"
                phase_map[str(b9)] = {"interfer": interfer, "grade": grade}
            else:
                phase_map[str(b9)] = {"interfer": -1, "grade": "误差"}
        # 保存 + 加载
        with open(PHASE_FILE, 'w') as f:
            json.dump(phase_map, f, indent=2)
        self.phase_calib = phase_map
        # 统计
        strong = sum(1 for v in phase_map.values() if v["grade"]=="强相消")
        self.add_log(f"✅ 相位校准完成: {strong}/18 频段强相消, 已存 nls_phase.json")
        return phase_map

    # ── 第六算法: 水分子团簇共振 (Stereo L/R差频) ──
    WATER_MODES = [  # 22个THz模式→Hz缩放 (fine_tune=432/110=3.9273)
        2.04, 3.42, 4.83, 7.78, 10.84, 13.94, 16.97, 19.56,
        24.16, 33.07, 48.31, 73.06, 99.76, 126.07, 164.17, 193.24,
        216.02, 256.46, 309.88, 362.89, 415.13, 432.00
    ]
    WATER_IDX = [0,2,4,6,8,9,10,11,12,13,14,15,16,17,18,19,20,21]  # 18阶子集

    def balance_water(self, deltas):
        """水分子团簇共振: 22模式→18阶子集, GC团簇尺寸决定振幅级跃
        小团簇(20-100): 聚焦低幅 0.3-0.5, 大团簇(1100+): 宏共振 1.0-1.5"""
        balanced = []
        sorted_items = sorted(deltas.items(), key=lambda x: -abs(x[1]))
        # 水团簇GC参数→团簇尺寸→振幅倍率 (非连续, 模拟相变跃迁)
        CLUSTER_AMP = [0.35, 0.45, 0.55, 0.70, 0.90, 1.15, 1.45]  # 七阶团簇振幅级跃
        for idx, (b9, delta) in enumerate(sorted_items[:18]):
            if abs(delta) < 3: continue
            organ, _, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
            w_idx = min(len(self.WATER_IDX)-1, max(0, (b9 - 14) * len(self.WATER_IDX) // 18))
            water_hz = self.WATER_MODES[self.WATER_IDX[w_idx]]
            # 团簇级跃: 小b9(低频)=大团簇(高振幅), 大b9(高频)=小团簇(低振幅)
            cluster_tier = min(6, max(0, (31 - b9) // 3))  # b9越小→tier越大
            amp_boost = CLUSTER_AMP[cluster_tier]
            # 三档频段 + 团簇级跃振幅
            if water_hz < 50:
                weight = 1.0; ch2_b9 = max(14, min(31, b9 + 2))
                adjust = min(60, int(abs(delta) * 0.5 * weight * amp_boost))
                b11, b15 = max(3, 15 - adjust), min(172, 15 + adjust)
            elif water_hz < 200:
                weight = 0.6; ch2_b9 = max(14, min(31, b9 + 1))
                adjust = min(60, int(abs(delta) * 0.5 * weight * amp_boost))
                b11, b15 = max(3, 15 - adjust), min(172, 15 + adjust)
            else:
                weight = 0.3; detune_hz = random.uniform(0.5, 2.5)
                adjust = min(60, int(abs(delta) * 0.4 * weight * amp_boost))
                b11, b15 = min(172, 15 + adjust), max(3, 15 - adjust)
                ch2_b9 = b9 + random.choice([-2,0,0,0,2,4]) if random.random() < 0.5 else b9
            if self.ser and self.ser.is_open:
                cmd = bytearray(128)
                cmd[9]=b9; cmd[11]=b11; cmd[13]=ch2_b9; cmd[15]=b15
                self._safe_write(cmd)
            balanced.append({"b9":b9,"organ":organ,"delta":delta,"direction":"💧",
                "ch1b9":b9, "ch1amp":b11, "ch2b9":ch2_b9, "ch2amp":b15})
            time.sleep(0.08)
        return balanced

    # ── 第七算法: Jellium电子壳层幻数共振 ──
    # 基于 1680=2^4·3·5·7 种子, 逐级解锁Jellium幻数: 2,8,20,28,40,50,34,68,92,138,184,322
    JELLIUM_SEQUENCE = [2,8,20,28,40,50,34,68,92,138,184,322]  # 全解锁序列
    JELLIUM_TIERS = [2,8,20,28,40]  # 第一梯队(1680种子自带6个)

    def balance_jellium(self, deltas):
        """Jellium幻数共振: 电子壳层能级 E_n∝1/n² 阶梯映射到振幅
        小幻数(2,8)=低能浅调制, 高幻数(92,138,322)=高能深调制, 非连续量子跃迁"""
        balanced = []
        # 量子能级阶梯: 每个tier对应离散振幅倍率(模拟 E_n = -13.6/n² 的跃迁)
        TIER_AMP = [0.25, 0.40, 0.60, 0.85, 1.15, 1.50, 1.85, 2.20, 2.60, 3.00, 3.40, 3.80]
        sorted_items = sorted(deltas.items(), key=lambda x: -abs(x[1]))
        for idx, (b9, delta) in enumerate(sorted_items[:20]):
            if abs(delta) < 2: continue
            organ, _, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
            tier = min(len(self.JELLIUM_SEQUENCE)-1, idx // 2)
            shell_num = self.JELLIUM_SEQUENCE[tier]
            # 量子跃迁振幅: 非连续, 每级有显著跳变
            amp_boost = TIER_AMP[tier]
            adjust = min(60, int(abs(delta) * amp_boost))
            # 幻数奇偶决定协议: 偶幻数→同频对称, 奇幻数→异频谐波+降幅
            if shell_num % 2 == 0:
                b11, b15 = max(3, 15 - adjust), min(172, 15 + adjust)
                ch2_b9 = b9
            else:
                b11 = max(3, 15 - adjust)
                b15 = min(172, 15 + adjust)
                ch2_b9 = min(31, b9 + (shell_num % 5) + 1)
            if self.ser and self.ser.is_open:
                cmd = bytearray(128)
                cmd[9]=b9; cmd[11]=b11; cmd[13]=ch2_b9; cmd[15]=b15
                self._safe_write(cmd)
            balanced.append({"b9":b9,"organ":organ,"delta":delta,"direction":"⚛",
                "ch1b9":b9, "ch1amp":b11, "ch2b9":ch2_b9, "ch2amp":b15})
            time.sleep(0.08)
        return balanced


    # ── 第八算法: 多谐波无理数比扫掠 ──
    def balance_multiharm(self, deltas):
        """多谐波扫掠: 5层谐波×2CH, CH1→φ偏移 CH2→√2偏移, 振幅逐层衰减
        CH1偏移: [0, +1, -2, +3, -1], CH2偏移: [0, -1, +2, -3, +1]
        差频两两之间比例趋近无理数, 5对×40ms=200ms/器官"""
        balanced = []
        ch1_offsets = [0, 1, -2, 3, -1]
        ch2_offsets = [0, -1, 2, -3, 1]
        amp_factors = [1.0, 0.80, 0.65, 0.50, 0.35]
        for b9, delta in sorted(deltas.items(), key=lambda x: -abs(x[1])):
            if abs(delta) < 3: continue
            organ, _, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
            corr = WUXING_CORRECTION.get(wuxing, 1.0)
            adjust = min(int(abs(delta) * 0.5 * corr), 60)
            b11_base = max(3, 15 - adjust) if delta > 0 else min(172, 15 + adjust)
            b15_base = min(172, 15 + adjust) if delta > 0 else max(3, 15 - adjust)
            for i in range(5):
                ch1b9 = max(14, min(31, b9 + ch1_offsets[i]))
                ch2b9 = max(14, min(31, b9 + ch2_offsets[i]))
                ch1amp = max(3, min(172, int(b11_base * amp_factors[i])))
                ch2amp = max(3, min(172, int(b15_base * amp_factors[i])))
                if self.ser and self.ser.is_open:
                    cmd = bytearray(128)
                    cmd[9]=ch1b9; cmd[11]=ch1amp; cmd[13]=ch2b9; cmd[15]=ch2amp
                    self._safe_write(cmd)
                if i < 4: time.sleep(0.04)
            balanced.append({"b9":b9,"organ":organ,"delta":delta,"direction":"🎵",
                "ch1b9": ch1b9, "ch1amp": ch1amp, "ch2b9": ch2b9, "ch2amp": ch2amp, "count": 5})
        return balanced

    # ── 第九算法: 五音疗愈 (宫商角徵羽→五行→b9异频双通道) ──
    WUYIN_MAP = [
        ("宫(土)", 22, 20, "土"),  # 脾 461k/922k 纯八度
        ("商(金)", 23, 22, "金"),  # 肺 326k/461k 近纯四度
        ("角(木)", 16, 15, "木"),  # 肝 3.69M/5.21M 近纯四度
        ("羽(水)", 30, 28, "水"),  # 肾 29k/58k 纯八度
        ("徵(火)", 27, 25, "火"),  # 心 81k/163k 纯八度
    ]
    _wuyin_idx = 0

    def balance_wuyin(self, deltas):
        """五音疗愈: 五行相生轮转, 异频双通道差拍干涉"""
        balanced = []
        sorted_items = sorted(deltas.items(), key=lambda x: -abs(x[1]))
        for b9, delta in sorted_items[:8]:
            if abs(delta) < 3: continue
            organ, _, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
            label, ch1b9, ch2b9, wx = self.WUYIN_MAP[self._wuyin_idx % len(self.WUYIN_MAP)]
            self._wuyin_idx += 1
            adjust = min(60, int(abs(delta) * 0.5 * self._wuxing_corr(wuxing)))
            b11 = max(3, min(172, 15 + adjust)) if delta > 0 else max(3, min(172, 15 - adjust))
            b15 = max(3, min(172, 15 - adjust)) if delta > 0 else max(3, min(172, 15 + adjust))
            if self.ser and self.ser.is_open:
                cmd = bytearray(128)
                cmd[9]=ch1b9; cmd[11]=b11; cmd[13]=ch2b9; cmd[15]=b15
                self._safe_write(cmd)
            balanced.append({"b9":b9,"organ":organ,"delta":delta,"direction":"🎵",
                "ch1b9": ch1b9, "ch1amp": b11, "ch2b9": ch2b9, "ch2amp": b15})
            self.add_log(f"  🎵{label} {organ} CH1={ch1b9} CH2={ch2b9}")
            time.sleep(0.08)
        return balanced

    # ═══════ 7族M集序列 (同步APK版 septetMix) ═══════
    F7_FEIGENBAUM = [1, 2, 4, 8, 16, 32]
    F7_FIBONACCI = [1, 1, 2, 3, 5, 8, 13, 21, 34]
    F7_SHARKOVSKY = [3,5,7,9,11,13,15,6,10,14,18,22,26,30,12,20,28,16,32]
    F7_FAREY = [2,3,3,4,5,4,5,6,5,6,7,7,8,9,10,11]
    F7_KNEADING = [2,3,5,7,11,13,17,19,23,29,31]
    F7_MISIUREWICZ = [4,6,9,14,21,25,30]
    F7_INTERNAL = [1,3,5,7,9,13,17,21,25,29]
    F7_ALL = [F7_FEIGENBAUM,F7_FIBONACCI,F7_SHARKOVSKY,F7_FAREY,F7_KNEADING,F7_MISIUREWICZ,F7_INTERNAL]

    @staticmethod
    def _nearest(b9, seq):
        return min(seq, key=lambda v: abs(b9 - v))

    @classmethod
    def _septet_mix(cls, b9):
        dists = [min(abs(b9 - v) for v in f) for f in cls.F7_ALL]
        w = [1.0 - d / (d + 4.0) if sum(dists) > 0 else 1.0 for d in dists]
        w1, w2 = w[0]+w[1]+w[2], w[3]+w[4]+w[5]+w[6]
        c1 = int(sum(cls._nearest(b9, cls.F7_ALL[i])*w[i] for i in range(3))/max(w1, 1e-6))
        c2 = int(sum(cls._nearest(b9, cls.F7_ALL[i])*w[i] for i in range(3,7))/max(w2, 1e-6))
        tw = sum(w)
        return (max(14, min(31, c1)), max(0.3, min(1.5, w1/tw*1.5)),
                max(14, min(31, c2)), max(0.3, min(1.5, w2/tw*1.5)))

    def balance_septet(self, deltas):
        """☀☽7族混音: CH1=结构族(Feigenbaum/Fibonacci/Sharkovsky), CH2=动力族(Farey/Kneading/Misiurewicz/InternalAddr)"""
        balanced = []
        for b9, delta in sorted(deltas.items(), key=lambda x: -abs(x[1]))[:12]:
            if abs(delta) < 4: continue
            organ, _, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
            corr = self._wuxing_corr(wuxing)
            adjust = min(60, int(abs(delta) * 0.5 * corr))
            base = self.cal_amp.get(str(b9), 15)
            c1, w1, c2, w2 = self._septet_mix(b9)
            if delta > 0:
                b11 = max(3, base - int(adjust * w1))
                b15 = min(172, base + int(adjust * w2))
            else:
                b11 = min(172, base + int(adjust * w1))
                b15 = max(3, base - int(adjust * w2))
            if self.ser and self.ser.is_open:
                cmd = bytearray(128)
                cmd[9] = c1; cmd[11] = b11; cmd[13] = c2; cmd[15] = b15
                self._safe_write(cmd)
            balanced.append({"b9":b9,"organ":organ,"delta":delta,"direction":"☀☽",
                "ch1b9":c1,"ch1amp":b11,"ch2b9":c2,"ch2amp":b15})
            self.add_log(f"  ☀☽7族 {organ} CH1={c1}w={w1:.2f} CH2={c2}w={w2:.2f}")
            time.sleep(0.08)
        return balanced

    def _treat_sleep(self, t=0.08):
        """治疗延时 = max(min_interval, 基础秒 × treat_speed)"""
        t_eff = max(self.min_interval, t * self.treat_speed)
        time.sleep(t_eff)

    # 治疗循环中的延时（从 verify 后开始）
    def verify(self, before):
        """复扫验证：对比治疗前后偏差"""
        imp, wors = 0, 0
        vrf = {}
        for b9, bf in before.items():
            if abs(bf) < 3:
                continue
            resp = self.probe(b9)
            avg = sum(resp) / 256 if len(resp) == 256 else 100
            after = round(avg - self.baseline.get(b9, 105), 1)
            diff = round(abs(bf) - abs(after), 1)
            vrf[b9] = {"before": bf, "after": after, "diff": diff}
            if diff > 0.5:
                imp += 1
            elif diff < -0.5:
                wors += 1
            # 更新单个器官验证结果
            organ, *_ = B9_MAP.get(b9, ('?', '?', '土'))
            with self._lock:
                if str(b9) in self.status["deltas"]:
                    self.status["deltas"][str(b9)]["verified"] = diff
            time.sleep(0.02)
        return vrf, imp, wors

    def calibrate(self):
        self.calibrating = True
        try:
            self.add_log(f"📐 校准基线中…传感器请悬空 (振幅={self.scan_amp})")
            raw = {}
            for b9 in B9_RANGE:
                amp = self.per_b9_amp.get(b9, self.scan_amp)  # per-b9优先
                resp = self.probe(b9, amp)
                raw[b9] = round(sum(resp) / len(resp), 1) if len(resp) == 256 else 105.0
            self.baseline = raw
            with open(BASELINE_FILE, 'w') as f:
                json.dump(raw, f, indent=2)
            self.add_log("✅ 基线校准完成")
            self.status["has_baseline"] = True
            # 相位曲线已存盘, 无需每次扫18频段
            if not self.phase_calib:
                self.calibrate_phase()
            else:
                strong = sum(1 for v in self.phase_calib.values() if v.get("grade") in ("强相消","中相消"))
                self.add_log(f"📡 相位曲线已加载: {strong}/18 强相消")
            if self._pending_start:
                self._pending_start = False
                self.add_log("⚡ 自动开始疗愈…")
                threading.Thread(target=self.loop, daemon=True).start()
            return raw
        except Exception as e:
            self.add_log(f"❌ 校准失败: {str(e)[:60]}")
        finally:
            self.calibrating = False

    def auto_tune_amp(self):
        """智能调谐: 在当前振幅下扫一遍, 根据每个频段偏离聚类中心的程度
        自动分配各自振幅——偏离大的保持, 偏离小的适度增压以增强信号分化"""
        self.calibrating = True
        try:
            time.sleep(1.0)  # 校准后冷却(铁律65: 轮间≥1.5s, 这里是新会话)
            self.add_log(f"🔧 智能调谐(振幅={self.scan_amp})…传感器请佩戴好")
            # ── 第1遍: 在当前振幅下扫描18频段 ──
            amp = self.scan_amp
            raw_vals = {}
            for b9 in B9_RANGE:
                if not self.ser or not self.ser.is_open:
                    self.add_log("⚠ 设备断开，调谐中止")
                    self.calibrating = False
                    return {"ok": False, "msg": "设备断开"}
                resp = self.probe(b9, amp)
                if len(resp) == 256:
                    v = sum(resp) / 256
                    if v > 20:  # 仅排除纯零值(探针失败), 允许弱信号
                        raw_vals[b9] = v
                time.sleep(0.08)
            valid_n = len(raw_vals)
            if valid_n < 16:
                self.add_log(f"⚠ 信号过弱: 仅{valid_n}/18频段有效 (需≥16), 调谐中止")
                self.calibrating = False
                return {"ok": False, "msg": f"有效信号不足({valid_n}/18), 检查传感器接触"}
            # ── 第2遍: 聚类分析, 按偏离度分配per-b9振幅 ──
            vals = list(raw_vals.values())
            svals = sorted(vals)
            median_v = svals[len(vals)//2]
            mad = sorted(abs(v - median_v) for v in vals)[len(vals)//2]
            effective_mad = max(mad, 0.8)  # 最小MAD保护
            spread = max(vals) - min(vals)
            self.add_log(f"  📊 基线质量: {valid_n}/18有效 范围{spread:.1f} 中位{median_v:.0f} MAD={mad:.1f}")
            # 按偏离度分三档: 近(埋没在噪声中) / 中 / 远(信号清晰)
            self.per_b9_amp = {}
            near_count = mid_count = far_count = 0
            for b9, v in raw_vals.items():
                dev = abs(v - median_v)
                if dev > 2.5 * effective_mad:
                    # 信号清晰→保持当前振幅即可
                    self.per_b9_amp[b9] = amp
                    far_count += 1
                elif dev > 1.5 * effective_mad:
                    # 中等信号→微增压 +10
                    self.per_b9_amp[b9] = min(amp + 10, 80)
                    mid_count += 1
                else:
                    # 信号弱→显著增压 +20
                    self.per_b9_amp[b9] = min(amp + 20, 80)
                    near_count += 1
            # 未扫到的b9用全局振幅兜底
            for b9 in B9_RANGE:
                if b9 not in self.per_b9_amp:
                    self.per_b9_amp[b9] = amp
            # ── 第3遍: 汇总 ──
            unique_amps = sorted(set(self.per_b9_amp.values()))
            amp_dist = {a: sum(1 for v in self.per_b9_amp.values() if v == a) for a in unique_amps}
            dist_str = ", ".join(f"{a}(×{amp_dist[a]})" for a in unique_amps)
            self.add_log(f"  🎯 偏离分配: 清晰×{far_count} 中等×{mid_count} 弱×{near_count}")
            self.add_log(f"✅ 调谐完成: 振幅分布 {dist_str}")
            # 振幅未变且无增压→基线可沿用
            if far_count == valid_n:
                self.add_log("   当前振幅已最优, 基线可沿用")
            else:
                self.status["has_baseline"] = False
                self.baseline = {}
                self.add_log(f"   振幅分布已更新, 需重新校准基线")
            self.status["scan_amp"] = amp
            self.calibrating = False
            return {"ok": True, "optimal_amp": amp, "tuned_b9": near_count + mid_count,
                    "per_b9_amp": {str(k): v for k, v in self.per_b9_amp.items()},
                    "quality": "good" if valid_n >= 17 else "ok"}
        except Exception as e:
            self.add_log(f"❌ 调谐失败: {str(e)[:60]}")
            self.calibrating = False
            return {"ok": False, "msg": str(e)[:80]}

    def diag_channels(self):
        """诊断CH1/CH2角色: 独立改变两通道参数看响应差异
        用probe()封装保证写入安全, 每轮检查连接状态"""
        if not self.ser or not self.ser.is_open:
            return {"ok": False, "msg": "设备未连接"}
        b9_test = 20
        tests = [
            ("A:低TX+低RX", b9_test, 15, b9_test, 15),
            ("B:高TX+低RX", b9_test, 60, b9_test, 15),
            ("C:低TX+高RX", b9_test, 15, b9_test, 60),
            ("D:高TX+高RX", b9_test, 60, b9_test, 60),
            ("E:异频TX≠RX", b9_test, 40, b9_test+5, 40),
        ]
        results = []
        self.add_log("🔬 信道诊断实验 (b9=20固定)…")
        for label, ch1b9, ch1amp, ch2b9, ch2amp in tests:
            if not self.ser or not self.ser.is_open:
                self.add_log(f"  {label}: ⚠ 设备已断开, 中止后续测试")
                break
            time.sleep(0.3)
            try:
                # 使用probe但覆写CH2参数
                cmd = bytearray(128)
                cmd[9]=ch1b9; cmd[11]=ch1amp; cmd[13]=ch2b9; cmd[15]=ch2amp
                self.ser.reset_input_buffer()
                if not self._safe_write(cmd):
                    self.add_log(f"  {label}: ⚠ 写入失败")
                    continue
                time.sleep(0.08)
                resp = list(self.ser.read(256))
                if len(resp) == 256:
                    avg = round(sum(resp)/256, 1)
                    rms = round((sum((b-128)**2 for b in resp)/256)**0.5, 1)
                    high = sum(1 for b in resp if b > 150)
                    results.append({"label": label, "avg": avg, "rms": rms, "high": high})
                    self.add_log(f"  {label}: avg={avg:.1f} RMS={rms:.1f} 高位={high}")
                else:
                    self.add_log(f"  {label}: ⚠ 响应长度{len(resp)}≠256")
            except Exception as e:
                self.add_log(f"  {label}: ❌ {e}")
        if len(results) >= 2:
            ba = results[1]["avg"] - results[0]["avg"] if len(results)>1 else 0
            ca = results[2]["avg"] - results[0]["avg"] if len(results)>2 else 0
            self.add_log(f"  分析: Δavg(B-A)={ba:+.1f} Δavg(C-A)={ca:+.1f}")
            if abs(ba) > abs(ca) * 1.5:
                self.add_log("  → CH1主导 (发射端), 增大b11=更强信号")
            elif abs(ca) > abs(ba) * 1.5:
                self.add_log("  → CH2主导 (接收端), 增大b15=更高灵敏度")
            else:
                self.add_log("  → 双信道贡献相当")
        elif results:
            self.add_log(f"  ⚠ 仅{len(results)}项完成, 结果不足以分析")
        return {"ok": True, "results": results}

    def loop(self):
        """主循环：扫描→治疗→验证，带错误保护"""
        self.running = True
        self.status["round"] = 0
        self.status["playing"] = True
        self._algo_queue = []
        self._batch_num = 0
        self.add_log("── 动态平衡启动 ──")
        try:
            while self.running:
                with self._lock:
                    self.status["round"] += 1
                    round_num = self.status["round"]
                    self.status["playing"] = True
                    self.status["scanning"] = True
                    self.status["deltas"] = {}

                # 第1步：扫描
                self.add_log(f"🔍 第{round_num}轮 扫描中…")
                deltas = self.scan()
                if not self.status["connected"] and not deltas:
                    self.add_log("⚠ 手环已断开，自动停止并保存")
                    self.running = False
                    break
                abn = {k: v for k, v in deltas.items() if abs(v) > 4}

                with self._lock:
                    self.status["scanning"] = False
                    self.status["deltas"] = self.status["deltas"]  # 已实时填充

                if abn:
                    algo = self.status.get("algo", "yinyang")
                    # 八维交替: 随机打乱每批出场顺序
                    if algo == "ab":
                        if not self._algo_queue:
                            self._batch_num += 1
                            self._algo_queue = ["original", "legacy", "yinyang", "fusion", "schumann", "water", "jellium", "multiharm", "wuyin", "septet"]
                            random.shuffle(self._algo_queue)
                            batch_labels = [({"original": "🔗原版", "legacy": "同频反相", "yinyang": "☀☽双频", "fusion": "⚡融合", "schumann": "🌍舒曼锚", "water": "💧水共振", "jellium": "⚛幻数", "multiharm": "🎵多谐波", "wuyin": "🎵五音", "septet": "☀☽7族混音"}[a]) for a in self._algo_queue]
                            self.add_log(f"─── 第{self._batch_num}遍: {' → '.join(batch_labels)} ───")
                        use_algo = self._algo_queue.pop(0)
                        self.status["current_algo"] = use_algo
                    else:
                        use_algo = algo
                    self.status["current_algo"] = use_algo

                    abn_list = sorted(abn.items(), key=lambda x: -abs(x[1]))
                    organ_names = [B9_MAP.get(b9, ('?', '?', ''))[0] for b9, _ in abn_list[:5]]
                    algo_labels = {"original": "🔗原版", "legacy": "同频反相", "yinyang": "☀☽双频", "fusion": "⚡融合", "schumann": "🌍舒曼锚", "water": "💧水共振", "jellium": "⚛幻数", "multiharm": "🎵多谐波", "wuyin": "🎵五音", "septet": "☀☽7族混音"}
                    algo_label = algo_labels.get(use_algo, use_algo)
                    self.add_log(f"  ⚡ {len(abn)}项异常: {', '.join(organ_names[:3])} [{algo_label}]")

                    if use_algo == "yinyang":
                        balanced = self.balance(deltas)
                    elif use_algo == "fusion":
                        balanced = self.balance_fusion(deltas)
                    elif use_algo == "schumann":
                        balanced = self.balance_schumann(deltas)
                    elif use_algo == "original":
                        balanced = self.balance_original_hybrid(deltas)
                    elif use_algo == "water":
                        balanced = self.balance_water(deltas)
                    elif use_algo == "jellium":
                        balanced = self.balance_jellium(deltas)
                    elif use_algo == "multiharm":
                        balanced = self.balance_multiharm(deltas)
                    elif use_algo == "wuyin":
                        balanced = self.balance_wuyin(deltas)
                    elif use_algo == "septet":
                        balanced = self.balance_septet(deltas)
                    else:
                        balanced = self.balance_legacy(deltas)

                    self.add_log(f"  🔊 已治 {len(balanced)}项")
                    # 音频反馈: 用第一个治疗项的CH1频率
                    if balanced:
                        self._audio_beep(balanced[0].get("ch1b9", balanced[0].get("b9", 20)), 
                                        balanced[0].get("ch2b9", balanced[0].get("b9", 20)), 100)
                    # 断连检测: 治疗过程中串口丢失则停止
                    if not self.ser or not self.ser.is_open:
                        self.running = False
                        self.add_log("⚠ 手环已断开！请重新连接后校准")
                        self.status["playing"] = False
                        self.status["connected"] = False
                        break
                    # 详细CH1/CH2日志
                    for item in balanced:
                        ch1b9 = item.get("ch1b9", item.get("b9", "?"))
                        ch1amp = item.get("ch1amp", "?")
                        ch2b9 = item.get("ch2b9", item.get("b9", "?"))
                        ch2amp = item.get("ch2amp", "?")
                        # f = 7.3728 / 2^((b9-14)/2) MHz → kHz
                        f1 = 7.3728 / (2 ** ((ch1b9 - 14) / 2)) * 1000  # kHz
                        f2 = 7.3728 / (2 ** ((ch2b9 - 14) / 2)) * 1000  # kHz
                        diff_f = abs(f1 - f2)
                        ratio = f"{ch1amp/ch2amp:.2f}" if isinstance(ch2amp, (int,float)) and ch2amp > 0 else "∞"
                        ch1f = f"{f1:.2f}k" if f1 < 1000 else f"{f1/1000:.2f}M"
                        ch2f = f"{f2:.2f}k" if f2 < 1000 else f"{f2/1000:.2f}M"
                        diffs = f"{diff_f:.2f}k" if diff_f < 1000 else f"{diff_f/1000:.2f}M"
                        dir_str = item.get("direction", "")
                        cnt = item.get("count", 1)
                        extra = f" ×{cnt}对" if cnt > 1 else ""
                        self.add_log(f"    {item.get('organ','?')} Δ={item.get('delta',0):+.1f} {dir_str}{extra} | CH1[b9={ch1b9} a={ch1amp} {ch1f}] CH2[b9={ch2b9} a={ch2amp} {ch2f}] Δf={diffs} a:R={ratio}")

                    # 第3步：验证
                    vrf, imp, wors = self.verify(deltas)
                    with self._lock:
                        self.status["improved"] = imp
                        self.status["worsened"] = wors
                        self.status["verify"] = {str(k): v for k, v in vrf.items()}
                        # A/B 统计
                        ab_key = "ab_" + use_algo
                        if ab_key in self.status:
                            self.status[ab_key]["imp"] += imp
                            self.status[ab_key]["wors"] += wors
                            self.status[ab_key]["rounds"] += 1

                    # 逐器官记录
                    for b9, v in sorted(vrf.items(), key=lambda x: -abs(x[1]["diff"])):
                        organ, *_ = B9_MAP.get(b9, ('?', '?', ''))
                        if v["diff"] > 0.5:
                            self.add_log(f"  ✅ {organ} ↓{abs(v['diff']):.1f}")
                        elif v["diff"] < -0.5:
                            self.add_log(f"  ⚠ {organ} ↑{abs(v['diff']):.1f}")

                    self.add_log(f"  [第{round_num}轮] 改善{imp} / 恶化{wors}  [{algo_label}]")

                    # 自动报告: 每种算法≥12轮时生成对比（7个算法×12轮=84轮）
                    if algo == "ab":
                        ab_stats = [
                            (self.status["ab_original"]["rounds"], "🔗原版"),
                            (self.status["ab_legacy"]["rounds"], "同频反相"),
                            (self.status["ab_yinyang"]["rounds"], "☀☽双频"),
                            (self.status["ab_fusion"]["rounds"], "⚡融合"),
                            (self.status["ab_schumann"]["rounds"], "🌍舒曼锚"),
                        ]
                        all_16 = all(r >= 16 for r, _ in ab_stats)
                        if all_16:
                            progress = ', '.join(f"{n}:{r}轮" for r, n in ab_stats)
                            self.add_log(f"  📊 每算法满16轮: {progress}  (已完{self._batch_num}遍)")
                            self._auto_report()
                            for ab_key in ["ab_original", "ab_legacy", "ab_yinyang", "ab_fusion", "ab_schumann", "ab_water", "ab_jellium", "ab_multiharm"]:
                                self.status[ab_key]["rounds"] = 0
                else:
                    self.add_log(f"  ✓ 第{round_num}轮 全部平衡")

                # 轮间间隔 + 每7轮自动保存快照
                if round_num % 7 == 0:
                    self._save_snapshot(silent=True)
                wait_end = time.time() + 0.5
                while self.running and time.time() < wait_end:
                    time.sleep(0.05)

        except Exception as e:
            self.add_log(f"❌ 错误: {str(e)[:80]}")
            traceback.print_exc()
        finally:
            self.running = False
            with self._lock:
                self.status["playing"] = False
                self.status["scanning"] = False
            self.add_log("⏹ 循环已停止")
            self._save_snapshot()  # 自动保存日志, 防止USB拔出丢失数据

    def _auto_report(self):
        org = self.status["ab_original"]
        leg = self.status["ab_legacy"]
        yy = self.status["ab_yinyang"]
        fus = self.status["ab_fusion"]
        sch = self.status["ab_schumann"]
        h2o = self.status["ab_water"]
        jel = self.status["ab_jellium"]
        coupling = self.status.get("coupling", 50)

        def rate(s):
            imp, wors, rds = s.get("imp", 0), s.get("wors", 0), s.get("rounds", 0)
            total = imp + wors
            pct = round(imp / total * 100, 1) if total > 0 else 0
            return pct, f"{pct}% ({rds}轮 改善{imp}/恶化{wors})"

        p_org, r_org = rate(org)
        p_leg, r_leg = rate(leg)
        p_yy, r_yy = rate(yy)
        p_fus, r_fus = rate(fus)
        p_sch, r_sch = rate(sch)
        p_h2o, r_h2o = rate(h2o)
        p_jel, r_jel = rate(jel)

        best = max([(p_org, "🔗原版"), (p_leg, "同频反相"), (p_yy, "☀☽双频"), (p_fus, "⚡融合"), (p_sch, "🌍舒曼锚"), (p_h2o, "💧水共振"), (p_jel, "⚛幻数")], key=lambda x: x[0])

        report = (
            f"╔═══ NLS 九维算法对比报告 (第{self._batch_num}遍) ═══\n"
            f"║ 时间: {time.strftime('%Y-%m-%d %H:%M:%S')}\n"
            f"║ 耦合度: {coupling}/100\n"
            f"║────────────────────────────────────────────\n"
            f"║ ① 🔗原版(PCAP): {r_org}\n"
            f"║ ② 同频反相: {r_leg}\n"
            f"║ ③ ☀☽双频:   {r_yy}\n"
            f"║ ④ ⚡融合:    {r_fus}\n"
            f"║ ⑤ 🌍舒曼锚:  {r_sch}\n"
            f"║ ⑥ 💧水共振:  {r_h2o}\n"
            f"║ ⑦ ⚛幻数:    {r_jel}\n"
            f"║────────────────────────────────────────────\n"
            f"║ 🏆 当前最优: {best[1]} ({best[0]}%)\n"
            f"╚══════════════════════════════════════════════\n"
        )
        self.add_log(report)

    def _save_snapshot(self, silent=False):
        """自动保存完整快照: AB统计+日志+扫描数据+五行+脊线+验证(使用缓存防扫描空窗)"""
        SNAPSHOT_DIR = os.path.join(os.path.dirname(__file__), "snapshots")
        os.makedirs(SNAPSHOT_DIR, exist_ok=True)
        ts = time.strftime('%Y%m%d_%H%M%S')
        p = os.path.join(SNAPSHOT_DIR, f"nls_snapshot_{ts}.json")
        ab = {}
        for k in ["ab_original","ab_legacy","ab_yinyang","ab_fusion","ab_schumann","ab_water","ab_jellium","ab_multiharm"]:
            s = self.status.get(k, {})
            ab[k] = {"imp": s.get("imp",0), "wors": s.get("wors",0), "rounds": s.get("rounds",0)}
        # 使用上次完整扫描的缓存(避免扫描瞬间deltas为空)
        cache = self._snapshot_cache
        snap = {
            "time": time.strftime('%Y-%m-%d %H:%M:%S'),
            "round": self.status.get("round", 0),
            "scan_time": cache.get("time", ""),
            "algo": self.status.get("algo", "ab"),
            "current_algo": self.status.get("current_algo", ""),
            "improved": self.status.get("improved", 0),
            "worsened": self.status.get("worsened", 0),
            "ab_stats": ab,
            "coupling": self.status.get("coupling", 50),
            "wuxing": cache.get("wuxing", []),
            "deltas": cache.get("deltas", []),
            "verify": {str(k): v for k, v in self.status.get("verify",{}).items()},
            "ridge": cache.get("ridge", {}),
            "log": self.status.get("log", [])[:50],
        }
        try:
            with open(p, 'w', encoding='utf-8') as f:
                json.dump(snap, f, ensure_ascii=False, indent=2)
            if not silent:
                self.add_log(f"💾 快照已存: {os.path.basename(p)}")
        except Exception as e:
            self.add_log(f"⚠ 快照保存失败: {e}")

    def disconnect(self):
        self.running = False
        self.status["connected"] = False
        if self.ser and self.ser.is_open:
            self.ser.close()
            self.ser = None


# ========== Web 界面 ==========
HTML = r"""<!DOCTYPE html><html lang="zh"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>NLS Self-Balancing</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui;background:#0a0a0f;color:#e0e0e0;max-width:640px;margin:0 auto;padding:10px}
h1{font-size:18px;text-align:center;color:#00ff88;margin:6px 0}
.card{background:#141420;border-radius:12px;padding:12px;margin:6px 0;border:1px solid #222}
.row{display:flex;justify-content:space-between;align-items:center;padding:4px 0;font-size:12px;border-bottom:1px solid #1a1a2e}
.row:last-child{border:0}
.badge{padding:1px 6px;border-radius:8px;font-size:10px;font-weight:bold;min-width:44px;text-align:center}
.conn-badge{padding:3px 10px;border-radius:12px;font-size:11px;font-weight:bold}
.btn{padding:10px 18px;border:0;border-radius:20px;font-size:13px;cursor:pointer;margin:3px;font-weight:bold;transition:opacity .2s}
.btn:disabled{opacity:.4}
.btn-start{background:#00ff88;color:#000}
.btn-stop{background:#ff4444;color:#fff}
.btn-cal{background:#333;color:#aaa}
.btns{text-align:center;margin:8px 0}
.log{font-size:10px;color:#666;max-height:120px;overflow-y:auto;margin-top:4px;line-height:1.4}
.log div{padding:1px 0;border-bottom:1px solid #0d0d14}
.stats{font-size:12px;color:#aaa;text-align:center;padding:4px 0}
.stats b{color:#00ff88}
canvas{display:block;margin:0 auto;border-radius:8px}
.tabs{display:flex;gap:4px;margin:6px 0}
.tab{padding:5px 12px;border-radius:12px;font-size:11px;cursor:pointer;background:#222;color:#888;border:0}
.tab.active{background:#7c4dff;color:#fff}
.view{display:none}
.view.active{display:block}
#dbg{font-size:9px;color:#f44;background:#2a0a0a;padding:4px 8px;border-radius:4px;margin:4px 0;display:none}
.overlay{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.85);z-index:100;display:flex;align-items:center;justify-content:center;flex-direction:column}
.overlay.hidden{display:none}
.overlay .box{background:#141420;border:2px solid #7c4dff;border-radius:16px;padding:24px;text-align:center;max-width:320px}
.overlay h2{color:#ffaa00;font-size:16px;margin-bottom:8px}
.overlay p{color:#aaa;font-size:13px;margin:8px 0;line-height:1.5}
.overlay .btn{display:inline-block;margin-top:12px;padding:12px 32px;font-size:15px}
</style></head><body>
<div class="overlay" id="calOverlay">
  <div class="box">
    <h2>⚠ 每次启动需重新校准基线</h2>
    <p>将手环<b style="color:#ff4444">完全悬空</b>（不接触任何皮肤/物体）<br>点击下方按钮开始自动扫描</p>
    <p style="font-size:11px;color:#f66">❗ 戴在手腕上校准 = 全部平衡，扫不出异常</p>
    <p style="font-size:11px;color:#666">手环需采集环境本底噪音<br>作为后续诊断的"零参考面"</p>
    <button class="btn btn-cal" id="calStartBtn" onclick="doCalibrate()" style="background:#7c4dff;color:#fff;font-size:15px;padding:14px 36px">📐 开始校准</button>
    <p id="calStatus" style="font-size:12px;color:#ffaa00;margin-top:12px"></p>
  </div>
</div>
<h1>🧬 NLS 动态平衡</h1>
<div id="conn_status" style="text-align:center;margin-bottom:6px">
  <span id="conn_badge" class="conn-badge" style="background:#333;color:#888">检测中...</span>
</div>
<div class="btns">
  <button class="btn btn-start" id="btnStart" onclick="api('/start')">▶ 启动平衡</button>
  <button class="btn btn-stop" id="btnStop" onclick="api('/stop')" style="display:none">⏹ 停止</button>
  <button class="btn btn-stop cmp-btn" id="btnCmp" onclick="stopAndCompare()" style="display:none">📊 停止&对比</button>
  <button class="btn btn-cal" onclick="api('/calibrate')">📐 校准基线</button>
  <button class="btn btn-cal" onclick="verifyPhase()" style="color:#ffaa00">🔬 验相位</button>
  <button class="btn btn-cal" onclick="diagCh()" style="color:#7c4dff;font-size:11px">🔬 信道诊断</button>
</div>
<div class="card" style="padding:8px 12px">
  <div style="display:flex;justify-content:space-between;align-items:center">
    <b>⚖ 九维对比</b>
    <span style="display:flex;align-items:center;gap:6px">
      <span id="baselineDot" style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#f44" title="基线状态"></span>
      <span id="couplingBadge" style="font-size:10px;color:#888">∿ --</span>
      <span id="entropyBadge" style="font-size:10px;color:#888">H --</span>
    </span>
  </div>
  <div style="display:flex;align-items:center;gap:6px;margin:4px 0">
    <span style="font-size:10px;color:#888">灵敏度</span>
    <input type="range" id="scanAmpSlider" min="3" max="80" value="20" style="flex:1;accent-color:#7c4dff;height:4px" oninput="setScanAmp(this.value)">
    <span id="scanAmpVal" style="font-size:11px;color:#aaa;min-width:28px">20</span>
    <button class="algo-btn" onclick="autoTune()" style="font-size:10px;padding:2px 8px;white-space:nowrap" title="自动扫10档振幅找最优性价比">🎯 一键最优</button>
  </div>
  <div style="display:flex;align-items:center;gap:6px;margin:4px 0">
    <span style="font-size:10px;color:#888">⚡治疗周期</span>
    <input type="range" id="speedSlider" min="1" max="12" value="1" style="flex:1;accent-color:#ff6600;height:4px" oninput="setSpeed(this.value)">
    <span id="speedVal" style="font-size:11px;color:#aaa;min-width:28px">×1</span>
  </div>
  <div style="display:flex;align-items:center;gap:6px;margin:3px 0">
    <span style="font-size:10px;color:#0f8">⏱ 最小间隔</span>
    <input type="range" id="minIntervalSlider" min="10" max="100" value="49" style="flex:1;accent-color:#0f8;height:4px" oninput="setMinInterval(this.value)">
    <span id="minIntervalVal" style="font-size:11px;color:#aaa;min-width:36px">0.49s</span>
  </div>
  <div style="display:flex;align-items:center;gap:6px;margin:3px 0">
    <span style="font-size:10px;color:#ffcc44">⏸ 长暂停</span>
    <input type="range" id="pauseThreshSlider" min="1" max="60" value="10" style="flex:1;accent-color:#ffcc44;height:4px" oninput="setPauseThreshold(this.value)">
    <span id="pauseThreshVal" style="font-size:11px;color:#aaa;min-width:32px">1.0s</span>
  </div>
  <div style="display:flex;align-items:center;gap:6px;margin:3px 0">
    <span style="font-size:10px;color:#ff8866">🔁 最长Burst</span>
    <input type="range" id="maxBurstSlider" min="1" max="12" value="8" style="flex:1;accent-color:#ff8866;height:4px" oninput="setMaxBurst(this.value)">
    <span id="maxBurstVal" style="font-size:11px;color:#aaa;min-width:22px">8</span>
  </div>
  <div style="display:flex;align-items:center;gap:6px;margin:3px 0">
    <span style="font-size:10px;color:#ff44aa">🔊 功率偏移</span>
    <input type="range" id="powerBoostSlider" min="0" max="5" value="0" style="flex:1;accent-color:#ff44aa;height:4px" oninput="setPowerBoost(this.value)">
    <span id="powerBoostVal" style="font-size:11px;color:#aaa;min-width:22px">+0</span>
  </div>
  <div style="display:flex;gap:4px;margin-top:4px;flex-wrap:wrap">
    <button class="algo-btn" onclick="setAlgo('original')" id="abORG">🔗原版</button>
    <button class="algo-btn" onclick="setAlgo('yinyang')" id="abYY">☀☽</button>
    <button class="algo-btn" onclick="setAlgo('legacy')" id="abOLD">同频</button>
    <button class="algo-btn" onclick="setAlgo('fusion')" id="abFUS">⚡融合</button>
    <button class="algo-btn" onclick="setAlgo('schumann')" id="abSCH">🌍锚</button>
    <button class="algo-btn" onclick="setAlgo('water')" id="abH2O">💧水</button>
    <button class="algo-btn" onclick="setAlgo('jellium')" id="abJEL">⚛幻数</button>
    <button class="algo-btn" onclick="setAlgo('multiharm')" id="abMH">🎵多谐波</button>
    <button class="algo-btn" onclick="setAlgo('wuyin')" id="abWY">🎵五音</button>
    <button class="algo-btn" onclick="setAlgo('septet')" id="abSEPT">☀☽7族</button>
    <button class="algo-btn active" onclick="setAlgo('ab')" id="abAB">🔄九维</button>
  </div>
  <div style="font-size:10px;color:#0f8;margin:6px 0 2px">
    🔗原版指纹: <select id="personSelect" onchange="switchPerson(this.value)" style="background:#1a1a2e;color:#0f8;border:1px solid #0f8;border-radius:3px;padding:2px 6px;font-size:12px">
    </select>
  </div>
  <div id="abStats" style="font-size:10px;color:#aaa;margin-top:4px"></div>
  <div id="abBars" style="margin-top:3px;display:none">
    <div style="display:flex;align-items:center;gap:4px;margin-bottom:2px">
      <span style="font-size:9px;color:#ffcc44;width:30px;text-align:right;flex-shrink:0">原版</span>
      <div style="flex:1;height:7px;border-radius:3px;background:#222"><div id="barOrg" style="background:#ffcc44;width:0%;height:100%;border-radius:3px;transition:width .4s,opacity .3s"></div></div>
    </div>
    <div style="display:flex;align-items:center;gap:4px;margin-bottom:2px">
      <span style="font-size:9px;color:#ffaa00;width:30px;text-align:right;flex-shrink:0">同频</span>
      <div style="flex:1;height:7px;border-radius:3px;background:#222"><div id="barLeg" style="background:#ffaa00;width:0%;height:100%;border-radius:3px;transition:width .4s,opacity .3s"></div></div>
    </div>
    <div style="display:flex;align-items:center;gap:4px;margin-bottom:2px">
      <span style="font-size:9px;color:#7c4dff;width:30px;text-align:right;flex-shrink:0">双频</span>
      <div style="flex:1;height:7px;border-radius:3px;background:#222"><div id="barYY" style="background:#7c4dff;width:0%;height:100%;border-radius:3px;transition:width .4s,opacity .3s"></div></div>
    </div>
    <div style="display:flex;align-items:center;gap:4px;margin-bottom:2px">
      <span style="font-size:9px;color:#44dd88;width:30px;text-align:right;flex-shrink:0">融合</span>
      <div style="flex:1;height:7px;border-radius:3px;background:#222"><div id="barFus" style="background:#44dd88;width:0%;height:100%;border-radius:3px;transition:width .4s,opacity .3s"></div></div>
    </div>
    <div style="display:flex;align-items:center;gap:4px">
      <span style="font-size:9px;color:#00aaff;width:30px;text-align:right;flex-shrink:0">锚定</span>
      <div style="flex:1;height:7px;border-radius:3px;background:#222"><div id="barSch" style="background:#00aaff;width:0%;height:100%;border-radius:3px;transition:width .4s,opacity .3s"></div></div>
    </div>
    <div style="display:flex;align-items:center;gap:4px;margin-bottom:2px">
      <span style="font-size:9px;color:#44ccff;width:30px;text-align:right;flex-shrink:0">水</span>
      <div style="flex:1;height:7px;border-radius:3px;background:#222"><div id="barH2O" style="background:#44ccff;width:0%;height:100%;border-radius:3px;transition:width .4s,opacity .3s"></div></div>
    </div>
    <div style="display:flex;align-items:center;gap:4px">
      <span style="font-size:9px;color:#ff6688;width:30px;text-align:right;flex-shrink:0">幻数</span>
      <div style="flex:1;height:7px;border-radius:3px;background:#222"><div id="barJEL" style="background:#ff6688;width:0%;height:100%;border-radius:3px;transition:width .4s,opacity .3s"></div></div>
    </div>
    <div style="display:flex;align-items:center;gap:4px">
      <span style="font-size:9px;color:#ff88cc;width:30px;text-align:right;flex-shrink:0">🎵</span>
      <div style="flex:1;height:7px;border-radius:3px;background:#222"><div id="barMH" style="background:#ff88cc;width:0%;height:100%;border-radius:3px;transition:width .4s,opacity .3s"></div></div>
    </div>
  </div>
</div>
<style>.algo-btn{padding:4px 7px;border-radius:8px;font-size:10px;cursor:pointer;background:#222;color:#888;border:0}.algo-btn.active{background:#7c4dff;color:#fff}.cmp-btn{background:#ff6600;color:#000;font-weight:bold}</style>
<div class="stats" id="stats"></div>
<div class="card" id="resultCard" style="display:none;border:2px solid #7c4dff">
  <b>📊 A/B 对比结论</b>
  <div id="resultBody" style="font-size:12px;line-height:1.6;margin-top:6px"></div>
</div>
<div id="dbg"></div>

<div class="tabs">
  <button class="tab active" onclick="switchTab('radar')">五行</button>
  <button class="tab" onclick="switchTab('ridge')">脊线</button>
  <button class="tab" onclick="switchTab('compare')">对比</button>
</div>

<div class="card view active" id="view_radar">
  <b style="color:#888;font-size:11px">☯ 五行平衡雷达图</b>
  <canvas id="cvRadar" width="584" height="260"></canvas>
</div>
<div class="card view" id="view_compare">
  <b style="color:#888;font-size:11px">⚖ 九维算法对比 (改善率)</b>
  <canvas id="cvCompare" width="584" height="280"></canvas>
</div>
<div class="card view" id="view_ridge">
  <b style="color:#888;font-size:11px">📈 频谱脊线图 (Pictograph)</b>
  <canvas id="cvRidge" width="584" height="200"></canvas>
</div>

<div class="card">
  <div style="display:flex;justify-content:space-between;align-items:center">
    <b>📝 日志</b>
    <div style="display:flex;gap:4px">
      <button onclick="copyLogs()" style="background:#333;color:#aaa;border:0;border-radius:8px;padding:3px 8px;font-size:10px;cursor:pointer">📋</button>
      <button onclick="clearLogs()" style="background:#333;color:#f66;border:0;border-radius:8px;padding:3px 8px;font-size:10px;cursor:pointer">🔄重记</button>
    </div>
  </div>
  <div class="log" id="log"></div>
</div>
<script>
var dbg=document.getElementById('dbg');
function debug(msg){dbg.style.display='block';dbg.textContent=msg;console.log(msg)}
function deltaColor(d){var a=Math.abs(d);return a>8?'#ff4444':a>4?'#ffaa00':'#00ff88'}

function drawRadar(items){
  var c=document.getElementById('cvRadar');
  if(!c){debug('cvRadar missing');return}
  var w=c.width,h=c.height,ctx=c.getContext('2d');
  ctx.clearRect(0,0,w,h);
  if(!items||!items.length){debug('radar:no items');ctx.fillStyle='#555';ctx.font='13px system-ui';ctx.textAlign='center';ctx.fillText('等待扫描数据…',w/2,h/2);return}

  // Aggregate by wuxing using index (avoid encoding issues)
  var names=['土','金','水','木','火'],colors=['#ffaa00','#aaa','#4488ff','#44ff44','#ff4444'];
  var sums=[0,0,0,0,0],cnts=[0,0,0,0,0];
  for(var i=0;i<items.length;i++){
    var wu=items[i].wuxing;
    for(var j=0;j<5;j++){if(wu===names[j]){sums[j]+=items[i].delta;cnts[j]++;break}}
  }
  var vals=[];for(var i=0;i<5;i++){vals.push(cnts[i]>0?sums[i]/cnts[i]:0)}
  //debug('radar:vals='+vals.map(function(v){return v.toFixed(1)}).join(',')+' sums='+sums.join(',')+' cnts='+cnts.join(','));

  var cx=w/2,cy=h/2+5,radius=Math.min(cx,cy)-50,n=5;
  var scaleMax=100; // fixed scale matching mobile coerceIn(-100,100)
  // Rings (white visible grid, matching phone v7.25)
  for(var ring=1;ring<=3;ring++){
    ctx.beginPath();ctx.strokeStyle='rgba(255,255,255,0.2)';ctx.lineWidth=1.5;
    for(var i=0;i<n;i++){
      var a=-Math.PI/2+2*Math.PI*i/n,rr=radius*ring/3;
      i==0?ctx.moveTo(cx+rr*Math.cos(a),cy+rr*Math.sin(a)):ctx.lineTo(cx+rr*Math.cos(a),cy+rr*Math.sin(a));
    }
    ctx.closePath();ctx.stroke();
  }
  // Axes + labels (colored wuxing, 2x size)
  for(var i=0;i<n;i++){
    var a=-Math.PI/2+2*Math.PI*i/n;
    ctx.beginPath();ctx.strokeStyle='rgba(255,255,255,0.2)';ctx.moveTo(cx,cy);ctx.lineTo(cx+radius*Math.cos(a),cy+radius*Math.sin(a));ctx.stroke();
    ctx.fillStyle=colors[i];ctx.font='bold 26px system-ui';ctx.textAlign='center';
    ctx.fillText(names[i],cx+(radius+28)*Math.cos(a),cy+(radius+28)*Math.sin(a)+9);
  }
  // Data polygon (green pentagon — mobile ratio: (val+100)/200 maps [-100,100]→[0,1])
  ctx.beginPath();ctx.strokeStyle='#00ff88';ctx.lineWidth=3;
  ctx.fillStyle='rgba(68,255,136,0.28)';
  var hasData=false;
  for(var i=0;i<n;i++){
    var a=-Math.PI/2+2*Math.PI*i/n,v=Math.max(-scaleMax,Math.min(scaleMax,vals[i]));
    var rr=radius*(v+scaleMax)/(2*scaleMax); // mobile ratio mapping → always visible
    if(Math.abs(v)>0.01)hasData=true;
    i==0?ctx.moveTo(cx+rr*Math.cos(a),cy+rr*Math.sin(a)):ctx.lineTo(cx+rr*Math.cos(a),cy+rr*Math.sin(a));
  }
  ctx.closePath();ctx.fill();ctx.stroke();
  // Vertex dots — at polygon vertices, colored by sign
  for(var i=0;i<n;i++){
    var a=-Math.PI/2+2*Math.PI*i/n,v=Math.max(-scaleMax,Math.min(scaleMax,vals[i]));
    var rr=radius*(v+scaleMax)/(2*scaleMax);
    ctx.beginPath();ctx.arc(cx+rr*Math.cos(a),cy+rr*Math.sin(a),6,0,Math.PI*2);
    ctx.fillStyle=v>0?'#ff4444':v<0?'#4488ff':'#888';ctx.fill();
    ctx.strokeStyle='#fff';ctx.lineWidth=2;ctx.stroke();
  }
  // Radar text summary
  var maxWX='',maxV=0,summaries=[];
  for(var i=0;i<5;i++){
    if(Math.abs(vals[i])>maxV){maxV=Math.abs(vals[i]);maxWX=names[i]}
    if(Math.abs(vals[i])>1)summaries.push(names[i]+(vals[i]>0?'↑':'↓')+Math.abs(vals[i]).toFixed(1));
  }
  if(summaries.length>0){
    ctx.fillStyle='#aaa';ctx.font='11px system-ui';ctx.textAlign='center';
    ctx.fillText(summaries.join('  '),w/2,cy+22);
  }
  ctx.fillStyle='#555';ctx.font='10px system-ui';ctx.textAlign='left';
  ctx.fillText('🔴 外=偏盛 | 🔵 内=偏弱',10,h-8);
  if(!hasData){ctx.fillStyle='#555';ctx.font='13px system-ui';ctx.textAlign='center';ctx.fillText('所有五行均值接近零',w/2,cy+radius+30)}
}

function drawCompare(items){
  var c=document.getElementById('cvCompare'),w=c.width,h=c.height,ctx=c.getContext('2d');
  ctx.clearRect(0,0,w,h);
  ctx.fillStyle='#0a0a14';ctx.fillRect(0,0,w,h);
  // Get algo stats from the global AB stats
  var ab=document.getElementById('abStats');
  if(!items||items.length<3){
    ctx.fillStyle='#555';ctx.font='13px system-ui';ctx.textAlign='center';
    ctx.fillText('等待统计…(需完成至少一轮九维扫描)',w/2,h/2);
    return;
  }
  var algoColors=['#ffcc44','#ff6644','#ffaa00','#44ff44','#4488ff','#44ccff','#ff6688','#ff88cc'];
  var algoNames={original:'🔗原版',legacy:'同频反相',yinyang:'☀☽双频',fusion:'⚡融合',schumann:'🌍舒曼锚',water:'💧水共振',jellium:'⚛幻数',multiharm:'🎵多谐波',wuyin:'🎵五音'};
  var barH=(h-40)/Math.max(items.length,1);
  for(var i=0;i<items.length;i++){
    var d=items[i],total=Math.max(1,d.imp+d.wors),rate=d.imp/total;
    var y=30+i*barH,fullW=w-170;
    // Name
    ctx.fillStyle='#aaa';ctx.font='12px system-ui';ctx.textAlign='left';
    ctx.fillText(algoNames[d.key]||d.key,10,y+barH/2+4);
    // BG
    ctx.fillStyle='#222';ctx.fillRect(130,y+2,fullW,barH-4);
    // Bar
    ctx.fillStyle=algoColors[i%algoColors.length];
    ctx.fillRect(130,y+2,Math.max(2,fullW*rate),barH-4);
    // Stats
    ctx.fillStyle='#00ff88';ctx.font='11px system-ui';
    ctx.fillText(Math.round(rate*100)+'%  ↑'+d.imp+' ↓'+d.wors,136,y+barH/2+4);
  }
}

var curTab='radar';
function switchTab(name){
  curTab=name;
  document.querySelectorAll('.tab').forEach(function(t){t.classList.remove('active')});
  document.querySelectorAll('.view').forEach(function(v){v.classList.remove('active')});
  event.target.classList.add('active');
  document.getElementById('view_'+name).classList.add('active');
  if(name==='radar'||(lastData.length>0&&name==='list')) drawRadar(lastData);
  if(name==='ridge') fetch('/api/status').then(function(r){return r.json()}).then(function(s){
    var items=Object.values(s.deltas||{});
    drawRidge(items);
  });
  if(name==='compare'){
    // Use the AB stats from poll
    var ab=document.getElementById('abStats');
    var org=window._abStats_original||{},leg=window._abStats_legacy||{},yy=window._abStats_yinyang||{},
        fus=window._abStats_fusion||{},sch=window._abStats_schumann||{},h2o=window._abStats_water||{},
        jel=window._abStats_jellium||{},mh=window._abStats_multiharm||{};
    drawCompare([
      {key:'original',imp:org.imp||0,wors:org.wors||0},
      {key:'legacy',imp:leg.imp||0,wors:leg.wors||0},
      {key:'yinyang',imp:yy.imp||0,wors:yy.wors||0},
      {key:'fusion',imp:fus.imp||0,wors:fus.wors||0},
      {key:'schumann',imp:sch.imp||0,wors:sch.wors||0},
      {key:'water',imp:h2o.imp||0,wors:h2o.wors||0},
      {key:'jellium',imp:jel.imp||0,wors:jel.wors||0},
      {key:'multiharm',imp:mh.imp||0,wors:mh.wors||0}
    ]);
  }
}

function drawRidge(items){
  var c=document.getElementById('cvRidge'),w=c.width,h=c.height,ctx=c.getContext('2d');
  ctx.clearRect(0,0,w,h);
  if(!items||items.length<5){
    ctx.fillStyle='#555';ctx.font='13px system-ui';ctx.textAlign='center';
    ctx.fillText('等待扫描数据…(需≥5频段)',w/2,h/2);
    return;
  }
  // Sort by b9 ascending
  var sorted=items.slice().sort(function(a,b){return a.b9-b.b9});
  var maxDelta=Math.max.apply(null,sorted.map(function(d){return Math.abs(d.delta)}))||1;
  var barW=(w-40)/sorted.length;
  ctx.fillStyle='#0a0a14';ctx.fillRect(0,0,w,h);
  // Draw baseline
  ctx.strokeStyle='rgba(255,255,255,0.3)';ctx.lineWidth=1.5;
  var midY=h/2;
  ctx.beginPath();ctx.moveTo(20,midY);ctx.lineTo(w-10,midY);ctx.stroke();
  // Draw frequency band dividers (高频→低频)
  [18,25].forEach(function(b9_ref){
    var idx=sorted.findIndex(function(d){return d.b9>=b9_ref});
    if(idx>=0){
      var x=20+idx*barW;
      ctx.strokeStyle='rgba(255,255,255,0.08)';ctx.lineWidth=1;
      ctx.setLineDash([3,6]);
      ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,h);ctx.stroke();
      ctx.setLineDash([]);
      ctx.fillStyle='rgba(255,255,255,0.2)';ctx.font='9px system-ui';ctx.textAlign='left';
      var labels={18:'~1.8MHz',25:'~160kHz'};
      ctx.fillText(labels[b9_ref]||'',x+2,12);
    }
  });
  // Draw frequency bars
  for(var i=0;i<sorted.length;i++){
    var d=sorted[i],ratio=d.delta/maxDelta;
    var x=20+i*barW,barH=Math.abs(ratio)*(h/2-10);
    var color=d.delta>0?'#ff444477':'#4488ff77';
    ctx.fillStyle=color;
    ctx.fillRect(x+1,d.delta>0?midY-barH:midY,barW-2,barH);
  }
  // Labels
  ctx.fillStyle='#555';ctx.font='10px system-ui';ctx.textAlign='center';
  ctx.fillText('f↑ 7.4MHz (高频)  ←  b9  →  20kHz 低频)',w/2,16);
  ctx.fillText('频段偏差谱线 (红↑偏盛 蓝↓不足)',w/2,h-4);
  // Show coupling at top right
  var cp=50;ctx.fillStyle='#00ff8888';ctx.font='bold 10px system-ui';ctx.textAlign='right';
  ctx.fillText('∿',w-10,14);
}

var lastData=[],ridgeCache=[];

function api(path){fetch(path).then(function(){poll()}).catch(function(e){debug('API错误:'+e)})}
var calDone=false, calRunning=false, baselineEverDone=false;

function setScanAmp(v){
  document.getElementById('scanAmpVal').textContent=v;
  fetch('/set_scan_amp/'+v).then(function(r){return r.json()}).then(function(d){
    if(d.ok){document.getElementById('baselineDot').style.background='#f44'}
  });
}

function setSpeed(v){
  document.getElementById('speedVal').textContent='×'+v;
  fetch('/set_speed/'+v);
}

function setMinInterval(v){
  var val=(v/100).toFixed(2);
  document.getElementById('minIntervalVal').textContent=val+'s';
  fetch('/set_min_interval/'+v);
}

function setPauseThreshold(v){
  var val=(v/10).toFixed(1);
  document.getElementById('pauseThreshVal').textContent=val+'s';
  fetch('/set_pause_thresh/'+v);
}

function setMaxBurst(v){
  document.getElementById('maxBurstVal').textContent=v;
  fetch('/set_max_burst/'+v);
}

function setPowerBoost(v){
  document.getElementById('powerBoostVal').textContent='+'+v;
  fetch('/set_power_boost/'+v);
}

function autoTune(){
  var btn=document.querySelector('[onclick=\"autoTune()\"]');
  var orig=btn.textContent; btn.textContent='扫描中…'; btn.disabled=true;
  fetch('/auto_tune').then(function(r){return r.json()}).then(function(d){
    if(d.ok){
      document.getElementById('scanAmpSlider').value=d.optimal_amp;
      document.getElementById('scanAmpVal').textContent=d.optimal_amp;
      document.getElementById('baselineDot').style.background='#f44';
      // 显示per-b9分布
      var info=d.tuned_b9+'/18频段独立调谐';
      if(d.per_b9_amp){
        var amps={};Object.keys(d.per_b9_amp).forEach(function(k){var a=d.per_b9_amp[k];amps[a]=(amps[a]||0)+1});
        info+=', 分布:'+JSON.stringify(amps);
      }
    }
    btn.textContent=orig; btn.disabled=false;
  }).catch(function(){btn.textContent=orig; btn.disabled=false});
}

function doCalibrate(){
  if(calRunning)return;calRunning=true;
  var btn=document.getElementById('calStartBtn'),st=document.getElementById('calStatus');
  btn.disabled=true;btn.textContent='校准中…';st.textContent='正在扫描 18 个频段…';
  fetch('/calibrate').then(function(r){return r.json()}).then(function(d){
    if(d.ok){st.innerHTML='<b style=\"color:#00ff88\">✅ 校准成功!</b>';calDone=true;baselineEverDone=true;setTimeout(function(){document.getElementById('calOverlay').classList.add('hidden')},1200)}
    else{st.textContent='❌ 失败: '+d.msg;btn.disabled=false;btn.textContent='📐 重试'}
  }).catch(function(){st.textContent='❌ 网络错误';btn.disabled=false;btn.textContent='📐 重试'}).finally(function(){calRunning=false});
}

function verifyPhase(){
  var s=document.getElementById('calStatus');
  s.textContent='🔬 相位验证中…(测试3个频点)';
  fetch('/verify_phase').then(function(r){return r.json()}).then(function(d){
    if(d.error){s.innerHTML='<b style=color:#f44>'+d.error+'</b>';return}
    var lines=d.map(function(r){
      if(r.error) return 'b9='+r.b9+' ❌ '+r.error;
      return 'b9='+r.b9+' CH1='+r.ch1_only+' 同相='+r.iphase+' 反相='+r.anti+' CH2='+r.ch2_only+' → '+r.verdict;
    });
    s.innerHTML='<b style=color:#0f8>结果:</b><br>'+lines.join('<br>');
  }).catch(function(){s.textContent='❌ 网络错误'});
}

function diagCh(){
  api('/diag_channels');
}

function setAlgo(mode){
  document.querySelectorAll('.algo-btn').forEach(function(b){b.classList.remove('active')});
  document.getElementById(mode==='yinyang'?'abYY':mode==='legacy'?'abOLD':mode==='fusion'?'abFUS':mode==='original'?'abORG':mode==='schumann'?'abSCH':mode==='water'?'abH2O':mode==='jellium'?'abJEL':mode==='multiharm'?'abMH':'abAB').classList.add('active');
  // 只停止+清空+切模式，不自动启动
  fetch('/stop').then(function(){
    return fetch('/clear_log');
  }).then(function(){
    return fetch('/algo?mode='+mode);
  }).then(poll);
}
function switchPerson(name){
  fetch('/person?name='+encodeURIComponent(name)).then(r=>r.json()).then(d=>{
    if(d.ok) document.getElementById('personLabel').textContent=name;
  });
}
function loadPersons(){
  fetch('/persons').then(r=>r.json()).then(d=>{
    var s=document.getElementById('personSelect');
    s.innerHTML='';
    d.persons.forEach(function(p){
      var o=document.createElement('option');
      o.value=p; o.textContent=p;
      if(p===d.current) o.selected=true;
      s.appendChild(o);
    });
  });
}
loadPersons();
}

function clearLogs(){
  if(!confirm('清空全部日志并重新开始？'))return;
  fetch('/clear_log').then(function(){
    document.getElementById('log').innerHTML='<div style=\"color:#555;padding:10px\">📝 日志已清空，等待新记录…</div>';
    poll();
  });
}

function copyLogs(){
  // 直接从已渲染的DOM读取日志（避免异步fetch破坏剪贴板权限）
  var logLines=document.getElementById('log').innerText.split('\n').filter(function(l){return l.trim()});
  var text='NLS 动态平衡 运行日志\n'+new Date().toLocaleString()+'\n'+'='.repeat(40)+'\n'+logLines.join('\n');
  var btn=event&&event.target||document.querySelector('[onclick*=\"copyLogs\"]');
  var orig=btn?btn.textContent:'📋';
  navigator.clipboard.writeText(text).then(function(){
    btn.textContent='✅ 已复制!';btn.style.color='#00ff88';
    setTimeout(function(){btn.textContent=orig;btn.style.color='#aaa'},1500);
  }).catch(function(){
    // 回退: textarea + execCommand
    var ta=document.createElement('textarea');
    ta.value=text;ta.style.cssText='position:fixed;left:-9999px;top:-9999px';
    document.body.appendChild(ta);ta.select();
    try{document.execCommand('copy');btn.textContent='✅ 已复制!';btn.style.color='#00ff88';
      setTimeout(function(){btn.textContent=orig;btn.style.color='#aaa'},1500)}
    catch(e2){alert('复制失败，请手动选择日志文本')}
    document.body.removeChild(ta);
  });
}

function stopAndCompare(){
  fetch('/stop').then(function(){return fetch('/api/status')}).then(function(r){return r.json()}).then(function(s){
    var leg=s.ab_legacy||{},yy=s.ab_yinyang||{},fus=s.ab_fusion||{};
    var legR=leg.rounds||0,yyR=yy.rounds||0,fusR=fus.rounds||0;
    if(legR+yyR+fusR<3){alert('至少运行 3 轮交替对比才能出结论');return}

    var legRate=legR>0?(leg.imp/(leg.imp+leg.wors||1)*100).toFixed(0):'-';
    var yyRate=yyR>0?(yy.imp/(yy.imp+yy.wors||1)*100).toFixed(0):'-';
    var fusRate=fusR>0?(fus.imp/(fus.imp+fus.wors||1)*100).toFixed(0):'-';

    var rates=[{name:'同频反相',rate:legRate,leg,color:'#ffaa00'},
               {name:'☀☽双频',rate:yyRate,yy,color:'#7c4dff'},
               {name:'⚡融合',rate:fusRate,fus,color:'#44dd88'}];
    rates.sort(function(a,b){return b.rate-a.rate});
    var winner=rates[0].rate>rates[1].rate?rates[0].name+(' 更好! (+'+(rates[0].rate-rates[1].rate)+'%)'):'难分伯仲';

    var card=document.getElementById('resultCard'),body=document.getElementById('resultBody');
    var h='<div style=\"display:flex;gap:6px;margin:8px 0\">';
    var colors={leg:'#ffaa00',yy:'#7c4dff',fus:'#44dd88'};
    var items=[{key:'leg',name:'同频反相',rate:legRate,d:leg,color:'#ffaa00'},
               {key:'yy',name:'☀☽双频',rate:yyRate,d:yy,color:'#7c4dff'},
               {key:'fus',name:'⚡融合',rate:fusRate,d:fus,color:'#44dd88'}];
    for(var i=0;i<items.length;i++){
      var it=items[i];
      h+='<div style=\"flex:1;background:#141420;padding:8px;border-radius:8px;text-align:center;border-top:3px solid '+it.color+'\">'+
        '<div style=\"color:#888;font-size:9px\">'+it.name+'</div>'+
        '<div style=\"font-size:20px;font-weight:bold;color:'+it.color+'\">'+it.rate+'%</div>'+
        '<div style=\"font-size:9px;color:#666\">'+it.d.rounds+'轮 改善'+it.d.imp+'/恶化'+it.d.wors+'</div></div>';
    }
    h+='</div><div style=\"text-align:center;font-size:14px;font-weight:bold;color:#00ff88;padding:8px\">🏆 '+winner+'</div>';
    body.innerHTML=h;
    card.style.display='block';
    card.scrollIntoView({behavior:'smooth'});
  });
}

function poll(){
  fetch('/api/status').then(function(r){return r.json()}).then(function(s){
    dbg.style.display='none';
    var cb=document.getElementById('conn_badge');
    cb.textContent=s.connected?'🟢 手环已连接':'⚪ 未连接';
    cb.style.background=s.connected?'#0a3a1a':'#333';
    cb.style.color=s.connected?'#00ff88':'#888';

    var btnS=document.getElementById('btnStart'),btnX=document.getElementById('btnStop'),btnC=document.getElementById('btnCmp');
    if(s.playing||s.scanning){btnS.style.display='none';btnX.style.display='inline-block';btnC.style.display='inline-block'}
    else{btnS.style.display='inline-block';btnX.style.display='none';btnC.style.display='none'}

    // Calibration overlay: only auto-show on FIRST load (never had baseline)
    var ov=document.getElementById('calOverlay');
    if(s.has_baseline) baselineEverDone = true;
    if(!s.has_baseline && !baselineEverDone && s.connected){
      ov.classList.remove('hidden');
    }

    // Baseline dot
    document.getElementById('baselineDot').style.background=s.has_baseline?'#00ff88':'#f44';
    // Sync scan amp slider (only if not currently dragging)
    if(s.scan_amp && document.getElementById('scanAmpVal')){
      var sl=document.getElementById('scanAmpSlider');
      if(document.activeElement!==sl){
        sl.value=s.scan_amp;
        document.getElementById('scanAmpVal').textContent=s.scan_amp;
      }
    }
    // Sync timing sliders
    if(s.min_interval!=null && document.getElementById('minIntervalVal')){
      var mi=Math.round(s.min_interval*100);
      var misl=document.getElementById('minIntervalSlider');
      if(document.activeElement!==misl){
        misl.value=mi; document.getElementById('minIntervalVal').textContent=s.min_interval.toFixed(2)+'s';
      }
    }
    if(s.pause_threshold!=null && document.getElementById('pauseThreshVal')){
      var pt=Math.round(s.pause_threshold*10);
      var ptsl=document.getElementById('pauseThreshSlider');
      if(document.activeElement!==ptsl){
        ptsl.value=pt; document.getElementById('pauseThreshVal').textContent=s.pause_threshold.toFixed(1)+'s';
      }
    }
    if(s.max_burst!=null && document.getElementById('maxBurstVal')){
      var mbsl=document.getElementById('maxBurstSlider');
      if(document.activeElement!==mbsl){
        mbsl.value=s.max_burst; document.getElementById('maxBurstVal').textContent=s.max_burst;
      }
    }
    if(s.power_boost!=null && document.getElementById('powerBoostVal')){
      var pbsl=document.getElementById('powerBoostSlider');
      if(document.activeElement!==pbsl){
        pbsl.value=s.power_boost; document.getElementById('powerBoostVal').textContent='+'+s.power_boost;
      }
    }

    var st=document.getElementById('stats');
    if(s.playing){
      var algoNames={original:'🔗原版',legacy:'同频反相',yinyang:'☀☽双频',fusion:'⚡融合',schumann:'🌍舒曼锚',water:'💧水共振',jellium:'⚛幻数',multiharm:'🎵多谐波',wuyin:'🎵五音'};
      var curAlgo=s.current_algo||s.algo;
      st.innerHTML='<b>● 第'+s.round+'轮</b> ['+algoNames[curAlgo]+'] | 改善<b>'+s.improved+'</b> 恶化<b>'+s.worsened+'</b>';
    }
    else if(!s.connected){st.innerHTML='<span style="color:#ffaa00">⚠ 未检测到手环</span>'}
    else if((s.log||[])[0]&&s.log[0].indexOf('✅ 基线')>=0){st.innerHTML='<b style="color:#00ff88">✅ 基线校准完成!</b>'}
    else if((s.log||[])[0]&&s.log[0].indexOf('📐 校准')>=0){st.innerHTML='<span style="color:#ffaa00">📐 校准中...</span>'}
    else{st.innerHTML='○ 待机'}

    // 九维对比统计 + 颜色条
    var ab=document.getElementById('abStats'),org=s.ab_original||{},leg=s.ab_legacy||{},yy=s.ab_yinyang||{},fus=s.ab_fusion||{},sch=s.ab_schumann||{},h2o=s.ab_water||{},jel=s.ab_jellium||{},mh=s.ab_multiharm||{};
    // Store for compare tab
    window._abStats_original=org;window._abStats_legacy=leg;window._abStats_yinyang=yy;
    window._abStats_fusion=fus;window._abStats_schumann=sch;window._abStats_water=h2o;
    window._abStats_jellium=jel;window._abStats_multiharm=mh;
    var orgRate=org.rounds>0?(org.imp/(org.imp+org.wors||1)*100).toFixed(0):'-';
    var legRate=leg.rounds>0?(leg.imp/(leg.imp+leg.wors||1)*100).toFixed(0):'-';
    var yyRate=yy.rounds>0?(yy.imp/(yy.imp+yy.wors||1)*100).toFixed(0):'-';
    var fusRate=fus.rounds>0?(fus.imp/(fus.imp+fus.wors||1)*100).toFixed(0):'-';
    var schRate=sch.rounds>0?(sch.imp/(sch.imp+sch.wors||1)*100).toFixed(0):'-';
    var h2oRate=h2o.rounds>0?(h2o.imp/(h2o.imp+h2o.wors||1)*100).toFixed(0):'-';
    var jelRate=jel.rounds>0?(jel.imp/(jel.imp+jel.wors||1)*100).toFixed(0):'-';
    var mhRate=mh.rounds>0?(mh.imp/(mh.imp+mh.wors||1)*100).toFixed(0):'-';
    var isAB = s.algo==='ab';
    var barIds=['barOrg','barLeg','barYY','barFus','barSch','barH2O','barJEL','barMH'];
    var curAlgoKey=s.current_algo||'';
    if(isAB){
      var algs=[
        {key:'original',name:'原版',rate:orgRate,stat:org,bar:'barOrg'},
        {key:'legacy',name:'同频',rate:legRate,stat:leg,bar:'barLeg'},
        {key:'yinyang',name:'☀☽',rate:yyRate,stat:yy,bar:'barYY'},
        {key:'fusion',name:'融合',rate:fusRate,stat:fus,bar:'barFus'},
        {key:'schumann',name:'锚',rate:schRate,stat:sch,bar:'barSch'},
        {key:'water',name:'水',rate:h2oRate,stat:h2o,bar:'barH2O'},
        {key:'jellium',name:'幻',rate:jelRate,stat:jel,bar:'barJEL'},
        {key:'multiharm',name:'🎵',rate:mhRate,stat:mh,bar:'barMH'}
      ];
      // 改善率排序
      algs.sort(function(a,b){return (b.rate=='-'?0:parseInt(b.rate))-(a.rate=='-'?0:parseInt(a.rate))});
      var abLines=algs.map(function(a){
        var rds=a.stat.rounds||0,imp=a.stat.imp||0,wors=a.stat.wors||0;
        var hl=a.key===curAlgoKey?' style=\"font-weight:bold;color:#fff\"':'';
        return '<span'+hl+'>'+a.name+'</span> <span style=\"color:#0f8;font-size:9px\">+'+imp+'</span><span style=\"color:#f44;font-size:9px\">-'+wors+'</span><b>'+a.rate+'%</b>';
      });
      ab.innerHTML='🏆 '+abLines.join('  ');
      barIds.forEach(function(id){var el=document.getElementById(id);if(el&&el.parentNode&&el.parentNode.parentNode){el.parentNode.parentNode.style.display=''}});
      // 突显当前算法bar
      barIds.forEach(function(id){var el=document.getElementById(id);if(el)el.style.opacity='1'});
      if(curAlgoKey){
        var curBarId={original:'barOrg',legacy:'barLeg',yinyang:'barYY',fusion:'barFus',schumann:'barSch',water:'barH2O',jellium:'barJEL',multiharm:'barMH'}[curAlgoKey];
        if(curBarId){var cel=document.getElementById(curBarId);if(cel)cel.style.opacity='1'}
      }
      document.getElementById('barOrg').style.width=(orgRate=='-'?0:Math.max(1,orgRate))+'%';
      document.getElementById('barLeg').style.width=(legRate=='-'?0:Math.max(1,legRate))+'%';
      document.getElementById('barYY').style.width=(yyRate=='-'?0:Math.max(1,yyRate))+'%';
      document.getElementById('barFus').style.width=(fusRate=='-'?0:Math.max(1,fusRate))+'%';
      document.getElementById('barSch').style.width=(schRate=='-'?0:Math.max(1,schRate))+'%';
      document.getElementById('barH2O').style.width=(h2oRate=='-'?0:Math.max(1,h2oRate))+'%';
      document.getElementById('barJEL').style.width=(jelRate=='-'?0:Math.max(1,jelRate))+'%';
      document.getElementById('barMH').style.width=(mhRate=='-'?0:Math.max(1,mhRate))+'%';
    } else {
      barIds.forEach(function(id){var el=document.getElementById(id);if(el&&el.parentNode&&el.parentNode.parentNode){el.parentNode.parentNode.style.display='none'}});
      var cur=s.algo||'yinyang';
      var curName={original:'🔗原版(PCAP)',legacy:'同频反相',yinyang:'☀☽双频',fusion:'⚡融合',schumann:'🌍舒曼锚',water:'💧水共振',jellium:'⚛幻数'}[cur]||cur;
      var curStat={original:org,legacy:leg,yinyang:yy,fusion:fus,schumann:sch,water:s.water||{imp:0,wors:0,rounds:0},jellium:s.jellium||{imp:0,wors:0,rounds:0}}[cur]||{imp:0,wors:0,rounds:0};
      var curImp=curStat.imp||0,curWors=curStat.wors||0,curRds=curStat.rounds||0;
      var curRate=curRds>0?(curImp/(curImp+curWors||1)*100).toFixed(0):'-';
      ab.innerHTML='<b>'+curName+'</b> | 改善<b style="color:#00ff88">'+curImp+'</b> 恶化<b style="color:#ff4444">'+curWors+'</b> ('+curRds+'轮) 率<b>'+curRate+'%</b>';
    }

    // Coupling badge
    var cp=s.coupling||50,cpColor=cp>70?'#00ff88':cp>40?'#ffaa00':'#ff4444';
    var cb=document.getElementById('couplingBadge');if(cb)cb.innerHTML='∿ <b style=\"color:'+cpColor+'\">'+cp+'</b>';
    var se=s.spectral_entropy||0.5,seColor=se>0.7?'#ffaa00':se>0.5?'#00ff88':'#4488ff';
    var eb=document.getElementById('entropyBadge');if(eb)eb.innerHTML='H <b style=\"color:'+seColor+'\">'+se.toFixed(3)+'</b>';
    var de=s.delta_eps||0,deColor=de>0.3?'#ffaa00':de>0?'#00ff88':'#888';
    var ni=s.nonlinear_ni||0,niColor=ni>0.15?'#ffaa00':ni>0.05?'#00ff88':'#888';
    var ba=document.getElementById('bioBadge')||document.createElement('span');
    ba.id='bioBadge';ba.style.cssText='font-size:10px;margin-left:8px';
    ba.innerHTML='Δε<b style=\"color:'+deColor+'\">'+de.toFixed(3)+'</b> NI<b style=\"color:'+niColor+'\">'+ni.toFixed(3)+'</b>';
    if(!document.getElementById('bioBadge')) document.getElementById('entropyBadge').parentNode.appendChild(ba);

    var items=Object.values(s.deltas||{});
    // Always sort once
    items.sort(function(a,b){return Math.abs(b.delta)-Math.abs(a.delta)});
    lastData=items;

    // Draw ridge chart if visible (after items is defined)
    if(items.length>=5) ridgeCache=items;
    if(curTab==='ridge') drawRidge(ridgeCache.length>=5?ridgeCache:items);

    if(items.length>0){
      try{drawRadar(items)}catch(e){debug('雷达图错误:'+e.message)}
    }

    // Deltas list (hidden, used for data, was previously visible in list tab)
    var dl=document.getElementById('deltas');
    if(dl){
      if(items.length>0){
        var h='';
        for(var i=0;i<items.length;i++){
          var o=items[i],ad=Math.abs(o.delta),c=ad>8?'#ff4444':ad>4?'#ffaa00':'#00ff88';
          var vfy=o.verified!=null?' <span style="font-size:9px;color:'+(o.verified>0?'#00ff88':'#ff4444')+'">'+(o.verified>0?'↓'+o.verified:'↑'+Math.abs(o.verified))+'</span>':'';
          var yy=' <span style=\"font-size:8px;color:#666\">'+(o.sun>o.moon?'☀'+o.sun_b9:'☽'+o.moon_b9)+'</span>';
          h+='<div class="row">'
            +'<span>'+o.organ+yy+'</span>'
            +'<span class="badge" style="color:'+c+'">Δ'+o.delta.toFixed(1)+vfy+'</span>'
            +'</div>';
        }
        dl.innerHTML=h;
      }else{dl.innerHTML='<div style="color:#555;text-align:center;padding:20px">等待扫描数据…</div>'}
    }

    var lg=document.getElementById('log');
    lg.innerHTML=(s.log||[]).slice(0,20).map(function(l){return'<div>'+l+'</div>'}).join('');
  }).catch(function(e){debug('状态解析错误:'+e)});
}
setInterval(poll,1500);poll();
// 页面关闭/刷新前自动保存日志
window.addEventListener('beforeunload',function(){
  navigator.sendBeacon('/save');
});
</script></body></html>"""


class WebHandler(BaseHTTPRequestHandler):
    balancer = None

    def log_message(self, *args): pass

    def send_json(self, data):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False, default=str).encode())

    def do_GET(self):
        self._route()
    def do_POST(self):
        self._route()
    def _route(self):
        p = urlparse(self.path)
        if p.path == "/":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(HTML.encode())
        elif p.path == "/api/status":
            with self.balancer._lock:
                self.send_json(self.balancer.status)
        elif p.path == "/start":
            # 自动重连
            if not self.balancer.ser or not self.balancer.ser.is_open:
                self.balancer.connect()
            if not self.balancer.status.get("has_baseline"):
                self.balancer._pending_start = True
                self.balancer.add_log("⏳ 等待校准完成，完成后自动开始…")
                self.balancer.status["deltas"] = {}
                self.send_json({"ok": True, "queued": True})
            elif not self.balancer.running:
                self.balancer.status["deltas"] = {}
                self.balancer.status["log"] = self.balancer.status["log"]
                threading.Thread(target=self.balancer.loop, daemon=True).start()
                self.send_json({"ok": True})
            else:
                self.send_json({"ok": True})
        elif p.path == "/stop":
            self.balancer.running = False
            with self.balancer._lock:
                self.balancer.status["playing"] = False
                self.balancer.status["scanning"] = False
            self.balancer._save_snapshot()
            self.send_json({"ok": True})
        elif p.path == "/algo":
            qs = parse_qs(p.query)
            mode = qs.get("mode", ["yinyang"])[0]
            with self.balancer._lock:
                self.balancer.status["algo"] = mode
                if mode != "ab":
                    self.balancer.status["ab_original"] = {"imp": 0, "wors": 0, "rounds": 0}
                    self.balancer.status["ab_legacy"] = {"imp": 0, "wors": 0, "rounds": 0}
                    self.balancer.status["ab_yinyang"] = {"imp": 0, "wors": 0, "rounds": 0}
                    self.balancer.status["ab_fusion"] = {"imp": 0, "wors": 0, "rounds": 0}
                    self.balancer.status["ab_schumann"] = {"imp": 0, "wors": 0, "rounds": 0}
                    self.balancer.status["ab_water"] = {"imp": 0, "wors": 0, "rounds": 0}
                    self.balancer.status["ab_jellium"] = {"imp": 0, "wors": 0, "rounds": 0}
                    self.balancer.status["ab_multiharm"] = {"imp": 0, "wors": 0, "rounds": 0}
            self.send_json({"ok": True, "algo": mode})
        elif p.path == "/persons":
            self.send_json({"persons": PERSONS, "current": CURRENT_PERSON})
        elif p.path == "/person":
            qs = parse_qs(p.query)
            person = qs.get("name", [PERSONS[0]])[0]
            if person in ALL_FINGERPRINTS:
                globals()["CURRENT_PERSON"] = person
                self.send_json({"ok": True, "person": person})
            else:
                self.send_json({"ok": False, "error": f"未知人员: {person}"})
        elif p.path == "/clear_log":
            with self.balancer._lock:
                self.balancer.status["log"] = []
                self.balancer.status["round"] = 0
                self.balancer.status["improved"] = 0
                self.balancer.status["worsened"] = 0
                self.balancer._batch_num = 0
                self.balancer._algo_queue = []
                self.balancer.status["ab_original"] = {"imp": 0, "wors": 0, "rounds": 0}
                self.balancer.status["ab_legacy"] = {"imp": 0, "wors": 0, "rounds": 0}
                self.balancer.status["ab_yinyang"] = {"imp": 0, "wors": 0, "rounds": 0}
                self.balancer.status["ab_fusion"] = {"imp": 0, "wors": 0, "rounds": 0}
                self.balancer.status["ab_schumann"] = {"imp": 0, "wors": 0, "rounds": 0}
                self.balancer.status["ab_water"] = {"imp": 0, "wors": 0, "rounds": 0}
                self.balancer.status["ab_jellium"] = {"imp": 0, "wors": 0, "rounds": 0}
                self.balancer.status["ab_multiharm"] = {"imp": 0, "wors": 0, "rounds": 0}
                self.balancer.status["coupling"] = 50
            self.send_json({"ok": True})
        elif p.path == "/verify_phase":
            result = self.balancer.calibrate_phase()
            self.send_json(result)
        elif p.path == "/auto_tune":
            if not self.balancer.ser or not self.balancer.ser.is_open:
                self.balancer.connect()
            if not self.balancer.ser or not self.balancer.ser.is_open:
                self.send_json({"ok": False, "msg": "手环未连接"})
                return
            if self.balancer.running:
                self.send_json({"ok": False, "msg": "请先停止循环"})
                return
            self.send_json({"ok": True, "msg": "调谐中…"})
            threading.Thread(target=lambda: self.balancer.auto_tune_amp(), daemon=True).start()
        elif p.path.startswith("/set_scan_amp/"):
            try:
                amp = int(p.path.split("/")[-1])
                amp = max(3, min(80, amp))  # clamp 3-80
                self.balancer.scan_amp = amp
                self.balancer.status["scan_amp"] = amp
                self.balancer.per_b9_amp = {}  # 手动调滑块→清除per-b9映射
                # 改振幅必须重新校准(基线需匹配)
                self.balancer.status["has_baseline"] = False
                self.balancer.baseline = {}
                self.balancer.add_log(f"⚙ 振幅已设为 {amp}（需重新校准基线）")
                self.send_json({"ok": True, "scan_amp": amp})
            except:
                self.send_json({"ok": False, "msg": "无效振幅值"})
        elif p.path.startswith("/set_speed/"):
            try:
                spd = max(1, min(12, float(p.path.split("/")[-1])))
                self.balancer.treat_speed = spd
                self.balancer.add_log(f"⚡ 治疗周期 ×{spd:.0f}")
                self.send_json({"ok": True, "speed": spd})
            except:
                self.send_json({"ok": False, "msg": "无效速度值"})
        elif p.path.startswith("/set_min_interval/"):
            try:
                v = int(p.path.split("/")[-1])  # centiseconds
                sec = max(10, min(100, v)) / 100.0
                self.balancer.min_interval = sec
                self.balancer.add_log(f"⏱ 最小间隔设为 {sec:.2f}s")
                self.send_json({"ok": True, "min_interval": round(sec, 2)})
            except:
                self.send_json({"ok": False, "msg": "无效间隔值"})
        elif p.path.startswith("/set_pause_thresh/"):
            try:
                v = int(p.path.split("/")[-1])  # deciseconds
                sec = max(1, min(60, v)) / 10.0
                self.balancer.pause_threshold = sec
                self.balancer.add_log(f"⏸ 长暂停阈值设为 {sec:.1f}s")
                self.send_json({"ok": True, "pause_threshold": round(sec, 1)})
            except:
                self.send_json({"ok": False, "msg": "无效阈值"})
        elif p.path.startswith("/set_max_burst/"):
            try:
                v = int(p.path.split("/")[-1])
                v = max(1, min(12, v))
                self.balancer.max_burst = v
                self.balancer.add_log(f"🔁 最长Burst设为 {v}")
                self.send_json({"ok": True, "max_burst": v})
            except:
                self.send_json({"ok": False, "msg": "无效burst值"})
        elif p.path.startswith("/set_power_boost/"):
            try:
                v = int(p.path.split("/")[-1])
                v = max(0, min(5, v))
                self.balancer.power_boost = v
                self.balancer.add_log(f"🔊 功率偏移 +{v}")
                self.send_json({"ok": True, "power_boost": v})
            except:
                self.send_json({"ok": False, "msg": "无效功率偏移"})
        elif p.path == "/diag_channels":
            if not self.balancer.ser or not self.balancer.ser.is_open:
                self.send_json({"ok": False, "msg": "手环未连接"})
                return
            self.send_json({"ok": True, "msg": "诊断中…"})
            threading.Thread(target=lambda: self.balancer.diag_channels(), daemon=True).start()
        elif p.path == "/save":
            self.balancer._save_snapshot()
            self.send_json({"ok": True})
        elif p.path == "/calibrate":
            # 自动重连手环（用户可能后插USB）
            if not self.balancer.ser or not self.balancer.ser.is_open:
                self.balancer.connect()
            if not self.balancer.ser or not self.balancer.ser.is_open:
                self.send_json({"ok": False, "msg": "手环未连接"})
                return
            if self.balancer.calibrating:
                self.send_json({"ok": False, "msg": "校准进行中，请稍候"})
                return
            threading.Thread(target=self.balancer.calibrate, daemon=True).start()
            self.send_json({"ok": True, "msg": "校准中…"})
        else:
            self.send_response(404)
            self.end_headers()


def main():
    b = Balancer()
    WebHandler.balancer = b
    server = HTTPServer(("0.0.0.0", 8080), WebHandler)
    server.socket.setsockopt(__import__('socket').SOL_SOCKET, __import__('socket').SO_REUSEADDR, 1)
    print("[OK] http://127.0.0.1:8080")

    # 后台尝试连接手环，先启动服务不阻塞
    def _bg_connect():
        time.sleep(0.3)
        if b.connect():
            print("[OK] COM4 已连接")
        else:
            print("[INFO] COM4 未连接 — Web界面已可用")

    threading.Thread(target=_bg_connect, daemon=True).start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        b.disconnect()
        server.server_close()


if __name__ == '__main__':
    main()
