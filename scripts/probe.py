import re, urllib.request, html as H
UA = {"User-Agent": "Mozilla/5.0"}
def get(u):
    try: return urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=40).read().decode("utf-8", "replace")
    except Exception as e: return "ERR " + str(e)
h = get("https://www.data.go.kr/data/15155058/openapi.do")
txt = re.sub(r"\s+", " ", H.unescape(re.sub(r"<[^>]+>", " ", h)))
for kw in ("상세기능", "요청주소", "위도", "좌표", "WGS", "Swagger", "oprtin"):
    for m in list(re.finditer(kw, txt))[:3]:
        print("CTX", kw, txt[max(0, m.start()-150):m.start()+250])
print("PATHS", sorted(set(re.findall(r"public_restroom_info_v2/[A-Za-z0-9_]+", h))))
print("JS", sorted(set(re.findall(r"(?:swaggerUrl|swagger[A-Za-z]*)\s*[:=]\s*['\"]([^'\"]+)", h)))[:5])
h2 = get("https://www.data.go.kr/data/15075531/fileData.do")
t = re.search(r"<title>([^<]+)</title>", h2); print("FILE15075531", t.group(1) if t else h2[:100])
