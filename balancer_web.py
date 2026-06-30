"""
NLS Self-Balancing Web v1.0 — 经络实时平衡仪 + Web仪表盘
启动: python balancer_web.py → 浏览器打开 http://localhost:8080
"""
import serial, struct, time, math, json, os, threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from collections import defaultdict
from urllib.parse import parse_qs, urlparse

COM_PORT = "COM4"
BASELINE_FILE = os.path.join(os.path.dirname(__file__), "nls_baseline.json")
B9_RANGE = range(14, 32)

WUXING_CORRECTION = {"木": 1.0, "火": 1.5, "土": 1.0, "金": 1.2, "水": 0.8}

B9_MAP = {
    14: ("骨骼/基础", "土"), 15: ("肌肉/结缔", "土"),
    16: ("皮肤/皮毛", "金"), 17: ("淋巴/免疫", "火"),
    18: ("消化/肠道", "金"), 19: ("消化/胃部", "土"),
    20: ("肝脏/代谢", "木"), 21: ("胰腺/内分泌", "土"),
    22: ("脾脏/血液", "土"), 23: ("肺部/呼吸", "金"),
    24: ("甲状腺", "火"), 25: ("肾脏/肾上腺", "水"),
    26: ("心血管", "火"), 27: ("心脏", "火"),
    28: ("神经系统", "火"), 29: ("脑/中枢", "火"),
    30: ("下丘脑", "火"), 31: ("松果体", "火"),
}

class Balancer:
    def __init__(self):
        self.ser = None
        self.baseline = {}
        self.running = False
        self.status = {"playing": False, "round": 0, "deltas": {}, "log": [], "improved": 0, "worsened": 0}
        self._lock = threading.Lock()
        self.load_baseline()
    
    def load_baseline(self):
        if os.path.exists(BASELINE_FILE):
            with open(BASELINE_FILE) as f:
                self.baseline = {int(k): v for k, v in json.load(f).items()}
    
    def connect(self):
        try:
            self.ser = serial.Serial(COM_PORT, 115200, timeout=2)
            return True
        except:
            return False
    
    def probe(self, b9, b11=15):
        cmd = bytearray(128)
        cmd[9] = b9; cmd[11] = b11; cmd[13] = b9; cmd[15] = b11
        self.ser.reset_input_buffer()
        self.ser.write(bytes(cmd))
        time.sleep(0.08)
        return list(self.ser.read(256))
    
    def scan(self):
        deltas = {}
        for b9 in B9_RANGE:
            resp = self.probe(b9)
            if len(resp) == 256:
                avg = sum(resp) / 256
                bl = self.baseline.get(b9, 105)
                deltas[b9] = round(avg - bl, 1)
        return deltas
    
    def balance(self, deltas):
        balanced = []
        for b9, delta in sorted(deltas.items(), key=lambda x: -abs(x[1])):
            if abs(delta) < 4: continue
            organ, wuxing = B9_MAP.get(b9, ('?', '土'))
            corr = WUXING_CORRECTION.get(wuxing, 1.0)
            base = 15
            adjust = min(int(abs(delta) * 0.5 * corr), 60)
            b11 = max(3, base - adjust) if delta > 0 else min(80, base + adjust)
            b15 = min(80, base + adjust) if delta > 0 else max(3, base - adjust)
            cmd = bytearray(128)
            cmd[9] = b9; cmd[11] = b11; cmd[13] = b9; cmd[15] = b15
            self.ser.write(bytes(cmd))
            balanced.append({'b9': b9, 'organ': organ, 'delta': delta, 'direction': '↓' if delta > 0 else '↑'})
            time.sleep(0.12)
        return balanced
    
    def verify(self, before):
        imp, wors = 0, 0
        vrf = {}
        for b9, bf in before.items():
            if abs(bf) < 3: continue
            resp = self.probe(b9)
            avg = sum(resp)/256 if len(resp)==256 else 100
            after = round(avg - self.baseline.get(b9, 105), 1)
            diff = abs(bf) - abs(after)
            vrf[b9] = {'before': bf, 'after': after, 'diff': round(diff,1)}
            if diff > 0.5: imp += 1
            elif diff < -0.5: wors += 1
        return vrf, imp, wors
    
    def calibrate(self):
        raw = {}
        for b9 in B9_RANGE:
            resp = self.probe(b9)
            raw[b9] = round(sum(resp)/len(resp), 1) if len(resp)==256 else 105
        self.baseline = raw
        with open(BASELINE_FILE, 'w') as f:
            json.dump(raw, f, indent=2)
        return raw
    
    def loop(self):
        self.running = True
        while self.running:
            with self._lock:
                self.status["round"] += 1
                round_num = self.status["round"]
            
            deltas = self.scan()
            abn = {b9:d for b9,d in deltas.items() if abs(d)>4}
            
            with self._lock:
                self.status["deltas"] = {str(k):v for k,v in deltas.items()}
                self.status["playing"] = True
            
            if abn:
                balanced = self.balance(deltas)
                vrf, imp, wors = self.verify(deltas)
                with self._lock:
                    self.status["improved"] = imp
                    self.status["worsened"] = wors
                    self.status["verify"] = {str(k):v for k,v in vrf.items()}
                    self.status["log"].insert(0, f"[{round_num:03d}] {len(abn)}项异常→已治 {len(balanced)}项→改善{imp}/恶化{wors}")
                    if len(self.status["log"]) > 50: self.status["log"].pop()
            else:
                with self._lock:
                    self.status["log"].insert(0, f"[{round_num:03d}] 全部平衡 ✓")
                    if len(self.status["log"]) > 50: self.status["log"].pop()
            
            time.sleep(3.0)  # 循环间隔
    
    def disconnect(self):
        self.running = False
        if self.ser: self.ser.close()


# ========== Web 服务器 ==========
HTML = """<!DOCTYPE html><html lang="zh"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>NLS Self-Balancing</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui;background:#0a0a0f;color:#e0e0e0;max-width:600px;margin:0 auto;padding:12px}
h1{font-size:18px;text-align:center;color:#00ff88;margin:8px 0}
.card{background:#141420;border-radius:12px;padding:14px;margin:8px 0;border:1px solid #222}
.row{display:flex;justify-content:space-between;padding:6px 0;font-size:14px;border-bottom:1px solid #1a1a2e}
.row:last-child{border:0}
.badge{padding:2px 8px;border-radius:10px;font-size:12px;font-weight:bold}
.g{color:#00ff88}.y{color:#ffaa00}.r{color:#ff4444}
.bar{height:6px;border-radius:3px;margin:2px 0;background:#222;overflow:hidden}
.bar-fill{height:100%;border-radius:3px;transition:width .3s}
.btn{padding:10px 20px;border:0;border-radius:20px;font-size:14px;cursor:pointer;margin:4px;font-weight:bold}
.btn-start{background:#00ff88;color:#000}
.btn-stop{background:#ff4444;color:#fff}
.btn-cal{background:#333;color:#aaa}
.btns{text-align:center;margin:12px 0}
.log{font-size:11px;color:#888;max-height:120px;overflow-y:auto;margin-top:8px}
.log div{padding:2px 0;border-bottom:1px solid #111}
</style></head><body>
<h1>🧬 NLS Self-Balancing</h1>
<div class="btns">
  <button class="btn btn-start" onclick="api('/start')">▶ 启动平衡</button>
  <button class="btn btn-stop" onclick="api('/stop')">⏹ 停止</button>
  <button class="btn btn-cal" onclick="api('/calibrate')">📐 校准基线</button>
</div>
<div class="card"><div id="status">等待启动...</div></div>
<div class="card"><b>📊 频段偏差</b><div id="deltas"></div></div>
<div class="card"><b>📝 日志</b><div class="log" id="log"></div></div>
<script>
async function api(path){await fetch(path);poll()}
async function poll(){
  try{
    let r=await fetch('/api/status'),s=await r.json();
    let st=document.getElementById('status'),dl=document.getElementById('deltas'),lg=document.getElementById('log');
    if(s.playing){
      st.innerHTML='<span style="color:#00ff88">● 平衡中</span> | 第'+s.round+'轮 | 改善'+s.improved+' 恶化'+s.worsened;
      let h=''; for(let[b,d]of Object.entries(s.deltas||{})){let c=Math.abs(d)>8?'r':Math.abs(d)>4?'y':'g'; h+='<div class=row><span>b9='+b+'</span><span class=badge style=color:'+(c=='g'?'#00ff88':c=='y'?'#ffaa00':'#ff4444')+'>Δ'+d+'</span></div>'} dl.innerHTML=h||'(无异常)';
    }else{
      st.innerHTML='<span style="color:#888">○ 待机</span>';
    }
    lg.innerHTML=s.log.slice(0,15).map(l=>'<div>'+l+'</div>').join('');
  }catch(e){}
}
setInterval(poll,2000);poll();
</script></body></html>"""

class WebHandler(BaseHTTPRequestHandler):
    balancer = None
    
    def log_message(self, *args): pass
    
    def send_json(self, data):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode())
    
    def do_GET(self):
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
            if not self.balancer.running:
                threading.Thread(target=self.balancer.loop, daemon=True).start()
            self.send_json({"ok": True})
        elif p.path == "/stop":
            self.balancer.running = False
            with self.balancer._lock:
                self.balancer.status["playing"] = False
            self.send_json({"ok": True})
        elif p.path == "/calibrate":
            self.balancer.calibrate()
            self.send_json({"ok": True, "baseline": self.balancer.baseline})
        else:
            self.send_response(404); self.end_headers()


def main():
    b = Balancer()
    if not b.connect():
        print("[FAIL] COM4 未连接")
        return
    print("[OK] COM4 已连接")
    
    WebHandler.balancer = b
    server = HTTPServer(("0.0.0.0", 8080), WebHandler)
    print("[OK] Web仪表盘: http://localhost:8080")
    
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        b.disconnect()
        server.server_close()

if __name__ == '__main__':
    main()
