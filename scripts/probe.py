"""공중화장실 API 주소 시험 (키는 앱 코드에서 읽고, 출력에서는 가림)"""
import re, urllib.request, urllib.error
src = open("app/src/main/java/kr/mytoilet/app/PublicApi.kt", encoding="utf-8").read()
K = re.search(r'KEY = "([^"]+)"', src).group(1)
bases = ["public_restroom_info_v2/info", "public_restroom_info_v2/getPublicRestroomInfo",
         "public_restroom_info/info", "PublicRestroomService/getPublicRestroomInfo", "public_restroom_info_v2"]
qs = ["pageNo=1&numOfRows=2&returnType=json", "pageNo=1&numOfRows=2&type=json", "pageNo=1&numOfRows=2"]
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
