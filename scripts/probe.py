"""공공데이터포털에서 전국 공중화장실 데이터 위치 찾기"""
import re, urllib.request, urllib.parse
H = {"User-Agent": "Mozilla/5.0"}
def get(u):
    try:
        return urllib.request.urlopen(urllib.request.Request(u, headers=H), timeout=40).read().decode("utf-8", "replace")
    except Exception as e:
        return "ERR " + str(e)
# 검색 결과 목록
for kind in ("API", "FILE"):
    u = "https://www.data.go.kr/tcs/dss/selectDataSetList.do?" + urllib.parse.urlencode(
        {"keyword": "공중화장실", "dType": kind, "perPage": "40", "currentPage": "1"})
    h = get(u)
    for m in re.finditer(r'href="(/data/(\d+)/(openapi|fileData)\.do)"[^>]*>\s*(?:<[^>]+>\s*)*([^<]{2,80})', h):
        print("LIST", kind, m.group(2), m.group(3), m.group(4).strip())
# 후보 상세 페이지
for cid in ("15155058", "15145697", "15112091", "15012892"):
    for kind in ("openapi", "fileData"):
        h = get(f"https://www.data.go.kr/data/{cid}/{kind}.do")
        t = re.search(r"<title>([^<]+)</title>", h)
        eps = sorted(set(re.findall(r"https?://apis?\.data\.go\.kr/[A-Za-z0-9_/\.]+", h)))
        print("PAGE", cid, kind, t.group(1).strip() if t else h[:80], eps[:6], "위도" in h, "좌표" in h)
