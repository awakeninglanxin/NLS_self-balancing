"""
NLS Dynamic Balancer v1.0 — 实时经络平衡仪
─────────────────────────────────────────
"耳机的降噪算法 × 扩散模型 × NLS手环"

使用:
  python dynamic_balancer.py
  
  默认: 循环扫描→反相治疗→复扫验证
  --once: 单次扫描+治疗
  --monitor: 持续监控，Δ超标才治疗
"""
import serial, struct, time, math, json, os, sys, argparse
from collections import defaultdict

# ========== 配置 ==========
COM_PORT = "COM4"
B9_RANGE = range(14, 32)  # 器官频段
BASELINE_FILE = "nls_baseline.json"

# 五行 → 反相系数 (火行衰减快，需要更强的反相)
WUXING_CORRECTION = {"木": 1.0, "火": 1.5, "土": 1.0, "金": 1.2, "水": 0.8}

# b9 → 器官 + 经络 + 五行
B9_MAP = {
    14: ("骨骼/基础", "基底", "土"),
    15: ("肌肉/结缔", "脾经", "土"),
    16: ("皮肤/皮毛", "肺经", "金"),
    17: ("淋巴/免疫", "三焦", "火"),
    18: ("消化/肠道", "大肠经", "金"),
    19: ("消化/胃部", "胃经", "土"),
    20: ("肝脏/代谢", "肝经", "木"),
    21: ("胰腺/内分泌", "脾经(胰)", "土"),
    22: ("脾脏/血液", "脾经(血)", "土"),
    23: ("肺部/呼吸", "肺经", "金"),
    24: ("甲状腺", "任脉", "火"),
    25: ("肾脏/肾上腺", "肾经", "水"),
    26: ("心血管", "心包经", "火"),
    27: ("心脏", "心经", "火"),
    28: ("神经系统", "督脉(神经)", "火"),
    29: ("脑/中枢", "督脉(脑)", "火"),
    30: ("下丘脑", "任脉", "火"),
    31: ("松果体", "督脉(松)", "火"),
}


class DynamicBalancer:
    def __init__(self):
        self.ser = None
        self.baseline = {}
        self.history = defaultdict(list)  # {(b9, organ): [deltas]}
        self.load_baseline()
    
    def load_baseline(self):
        if os.path.exists(BASELINE_FILE):
            with open(BASELINE_FILE) as f:
                data = json.load(f)
                self.baseline = {int(k): v for k, v in data.items()}
            print(f"[OK] 已加载基线 ({len(self.baseline)}频段)")
    
    def save_baseline(self):
        with open(BASELINE_FILE, 'w') as f:
            json.dump(self.baseline, f, indent=2)
        print(f"[OK] 基线已保存 → {BASELINE_FILE}")
    
    def calibrate(self):
        """空扫基线校准"""
        print("\n" + "="*50)
        print("  基线校准 — 传感器悬空，不接触皮肤")
        print("="*50)
        raw = {}
        for b9 in B9_RANGE:
            resp = self.probe(b9)
            avg = sum(resp) / len(resp) if resp else 100
            self.baseline[b9] = round(avg, 1)
            freq = 7.3728 * (2**(b9/4)) * 3
            print(f"  b9={b9:2d} {freq:>5.0f}MHz → avg={avg:.1f}")
        self.save_baseline()
    
    def probe(self, b9, b11=15):
        """发送探针，返回256字节响应"""
        cmd = bytearray(128)
        cmd[9] = b9; cmd[11] = b11; cmd[13] = b9; cmd[15] = b11
        self.ser.reset_input_buffer()
        self.ser.write(bytes(cmd))
        time.sleep(0.08)
        return list(self.ser.read(256))
    
    def scan(self):
        """扫描当前状态，返回各b9的Δ"""
        deltas = {}
        for b9 in B9_RANGE:
            resp = self.probe(b9)
            if len(resp) == 256:
                avg = sum(resp) / 256
                bl = self.baseline.get(b9, 105)
                deltas[b9] = round(avg - bl, 1)
        return deltas
    
    def balance(self, deltas):
        """反相治疗"""
        balanced = []
        for b9, delta in sorted(deltas.items(), key=lambda x: -abs(x[1])):
            if abs(delta) < 4:
                continue
            
            organ, meridian, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
            freq = 7.3728 * (2**(b9/4)) * 3
            corr = WUXING_CORRECTION.get(wuxing, 1.0)
            
            # 反相算法: 偏高→CH1降/CH2升, 偏低→CH1升/CH2降
            base = 15
            adjust = int(abs(delta) * 0.5 * corr)
            adjust = min(adjust, 60)
            
            if delta > 0:  # 偏高→抑制
                b11 = max(3, base - adjust)
                b15 = min(80, base + adjust)
                direction = "↓"
            else:  # 偏低→激发
                b11 = min(80, base + adjust)
                b15 = max(3, base - adjust)
                direction = "↑"
            
            cmd = bytearray(128)
            cmd[9] = b9; cmd[11] = b11; cmd[13] = b9; cmd[15] = b15
            self.ser.write(bytes(cmd))
            
            balanced.append({
                'b9': b9, 'organ': organ, 'meridian': meridian,
                'wuxing': wuxing, 'delta': delta, 'freq': freq,
                'b11': b11, 'b15': b15, 'direction': direction
            })
            time.sleep(0.15)
        return balanced
    
    def verify(self, before_deltas):
        """治疗后复扫验证"""
        print(f"\n{'=':->55}")
        print("  复扫验证")
        print(f"{'=':->55}")
        print(f"{'b9':<4} {'器官':<12} {'前Δ':>7} {'后Δ':>7} {'改善':>6}")
        print("-" * 40)
        
        improved = 0
        worsened = 0
        for b9 in sorted(before_deltas.keys()):
            before = before_deltas[b9]
            if abs(before) < 3:
                continue
            
            resp = self.probe(b9)
            avg = sum(resp) / 256 if len(resp) == 256 else 100
            after = round(avg - self.baseline.get(b9, 105), 1)
            
            diff = abs(before) - abs(after)
            if diff > 0.5:
                trend = "✅↑"
                improved += 1
            elif diff < -0.5:
                trend = "⚠↓"
                worsened += 1
            else:
                trend = "→"
            
            organ = B9_MAP.get(b9, ('?',))[0]
            print(f"  {b9:<4} {organ:<12} {before:>+6.1f} {after:>+6.1f} {diff:>+5.1f} {trend}")
        
        total = improved + worsened
        rate = improved / total * 100 if total > 0 else 0
        print(f"\n  改善率: {improved}/{total} ({rate:.0f}%)")
        return improved, worsened
    
    def loop(self, interval=5):
        """持续循环：扫描→治疗→验证"""
        print(f"\n{'='*50}")
        print("  动态平衡模式 — 每 {interval}s 循环一次")
        print(f"  Ctrl+C 停止")
        print(f"{'='*50}")
        
        round_num = 0
        try:
            while True:
                round_num += 1
                t0 = time.time()
                
                # 扫描
                deltas = self.scan()
                abn_count = sum(1 for d in deltas.values() if abs(d) > 4)
                
                if abn_count == 0:
                    print(f"[{round_num:03d}] 全部平衡 ✓")
                else:
                    # 治疗
                    balanced = self.balance(deltas)
                    print(f"[{round_num:03d}] {abn_count}项异常 → 已治疗 {len(balanced)}项 | ", end='')
                    for b in balanced[:3]:
                        organ = B9_MAP.get(b['b9'], ('?',))[0]
                        print(f"{organ}({b['delta']:+.1f}) ", end='')
                    print()
                    
                    # 验证
                    imp, wors = self.verify(deltas)
                    print(f"[{round_num:03d}] 改善{imp}/恶化{wors}")
                
                elapsed = time.time() - t0
                wait = max(0, interval - elapsed)
                time.sleep(wait)
        except KeyboardInterrupt:
            print("\n\n[STOP] 动态平衡已停止")
    
    def run_once(self):
        """单次：扫描→治疗→验证→报告"""
        print("\n🔍 扫描...")
        deltas = self.scan()
        
        abn = {b9: d for b9, d in deltas.items() if abs(d) > 4}
        print(f"异常频段: {len(abn)}/{len(deltas)}")
        for b9, d in sorted(abn.items(), key=lambda x: -abs(x[1])):
            organ, _, wuxing = B9_MAP.get(b9, ('?', '?', '?'))
            freq = 7.3728 * (2**(b9/4)) * 3
            print(f"  b9={b9:2d} {freq:>5.0f}MHz {organ:<12} {wuxing}行 Δ={d:+.1f}")
        
        if abn:
            print(f"\n🔊 反相治疗 {len(abn)} 项...")
            balanced = self.balance(deltas)
            
            print(f"\n🔍 复扫验证...")
            time.sleep(1)
            improved, worsened = self.verify(deltas)
            print(f"\n✅ 改善: {improved}/{improved+worsened} ({improved/(improved+worsened)*100:.0f}%)")
        else:
            print("\n✅ 所有频段平衡，无需治疗")
    
    def connect(self):
        try:
            self.ser = serial.Serial(COM_PORT, 115200, timeout=2)
            return True
        except:
            return False
    
    def disconnect(self):
        if self.ser:
            self.ser.close()


def main():
    p = argparse.ArgumentParser(description='NLS Dynamic Balancer')
    p.add_argument('--calibrate', action='store_true', help='空扫基线校准')
    p.add_argument('--once', action='store_true', help='单次扫描+治疗')
    p.add_argument('--monitor', action='store_true', help='持续监控(超标才治疗)')
    p.add_argument('--interval', type=int, default=5, help='循环间隔(秒)')
    args = p.parse_args()
    
    print("╔══════════════════════════════════════════╗")
    print("║  NLS Dynamic Balancer — 经络实时平衡仪  ║")
    print("╚══════════════════════════════════════════╝")
    
    b = DynamicBalancer()
    
    if not b.connect():
        print("[FAIL] COM4 未连接")
        return
    
    try:
        if args.calibrate:
            b.calibrate()
        elif args.once:
            b.run_once()
        else:
            # 默认: 持续循环
            if not b.baseline:
                print("[!] 无基线，先校准...")
                b.calibrate()
                input("\n贴手腕，按 Enter 开始...")
            b.loop(interval=args.interval)
    finally:
        b.disconnect()


if __name__ == '__main__':
    main()
