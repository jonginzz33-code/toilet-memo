"""공중화장실 API 주소 시험 (키는 앱 코드에서 읽고, 출력에서는 가림)"""
import re, urllib.request, urllib.error
src = open("app/src/main/java/kr/mytoilet/app/PublicApi.kt", encoding="utf-8").read()
K = re.search(r'KEY = "([^"]+)"', src).group(1)
bases = ["public_restroom_info_v2/info", "public_restroom_info_v2/getPublicRestroomInfo",
         "public_restroom_info/info", "PublicRestroomService/getPublicRestroomInfo", "public_restroom_info_v2"]
qs = ["pageNo=1&numOfRows=2&returnType=json", "pageNo=1&numOfRows=2&type=json", "pageNo=1&numOfRows=2"]
# 공공데이터포털 명세(swagger)에서 정식 주소 확인
for cid in ("15112091", "15155058", "15145697"):
    try:
        t = urllib.request.urlopen(f"https://data.go.kr/catalog/{cid}/openapi.json", timeout=30).read().decode("utf-8", "replace")
        import json
        d = json.loads(t)
        print("SPEC", cid, d.get("info", {}).get("title"), d.get("host"), d.get("basePath"), list(d.get("paths", {}).keys())[:5],
              [srv.get("url") for srv in d.get("servers", [])])
    except Exception as e:
        print("SPEC", cid, "ERR", str(e)[:200])

# 전국공중화장실 표준데이터 (좌표 포함)
for u0 in ("https://api.data.go.kr/openapi/tn_pubr_public_toilet_api", "http://api.data.go.kr/openapi/tn_pubr_public_toilet_api"):
    u = f"{u0}?serviceKey={K}&pageNo=1&numOfRows=2&type=json"
    try:
        r = urllib.request.urlopen(u, timeout=30); code = r.status; body = r.read()[:900]
    except urllib.error.HTTPError as e:
        code = e.code; body = e.read()[:900]
    except Exception as e:
        code = "ERR"; body = str(e).encode()
    print("PROBE", code, u0, body.decode("utf-8", "replace").replace(K, "***").replace("\n", " "))

bases = []
for b in bases:
    for q in qs:
        u = f"https://apis.data.go.kr/1741000/{b}?serviceKey={K}&{q}"
        try:
            r = urllib.request.urlopen(u, timeout=30); code = r.status; body = r.read()[:400]
        except urllib.error.HTTPError as e:
            code = e.code; body = e.read()[:400]
        except Exception as e:
            code = "ERR"; body = str(e).encode()
        txt = body.decode("utf-8", "replace").replace(K, "***").replace("\n", " ")
        print("PROBE", code, b, q, txt)
