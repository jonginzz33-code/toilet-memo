"""공공데이터포털 공중화장실 API를 받아 앱용 파일(public-toilets.json.gz)로 만든다.
앱의 PublicApi.parse 와 같은 규칙으로 항목을 고른다."""
import gzip, json, os, re, sys, time, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

KEY = "fa4fc859cd80c582d1cbf58943b918023182ed5d6e5f8d2f00b40dfdbcc0e9da"
URL = "https://apis.data.go.kr/1741000/public_restroom_info_v2/info_v2"
ROWS = 1000


def fetch(page):
    u = f"{URL}?serviceKey={KEY}&pageNo={page}&numOfRows={ROWS}&type=json&returnType=json&_type=json"
    for i in range(8):
        try:
            with urllib.request.urlopen(u, timeout=40) as r:
                return r.read().decode("utf-8")
        except Exception as e:
            print("retry", page, e, flush=True)
            time.sleep(2 + i * 2)
    raise SystemExit(f"page {page} failed")


def find_items(o):
    if isinstance(o, list):
        if o and isinstance(o[0], dict) and len(o[0]) >= 3:
            return o
        for x in o:
            r = find_items(x)
            if r is not None:
                return r
    elif isinstance(o, dict):
        for v in o.values():
            r = find_items(v)
            if r is not None:
                return r
    return None


def find_total(o):
    if isinstance(o, dict):
        for k, v in o.items():
            if k.lower() in ("totalcount", "total_count"):
                try:
                    return int(v)
                except Exception:
                    pass
            r = find_total(v)
            if r is not None:
                return r
    elif isinstance(o, list):
        for x in o:
            r = find_total(x)
            if r is not None:
                return r
    return None


def s(v):
    return "" if v is None else str(v).strip()


def pick(o, prefer, fallback, exclude=None):
    for lst in (prefer, fallback):
        for r in lst:
            for k in o:
                if re.search(r, k) and (exclude is None or not re.search(exclude, k)) and s(o[k]) and s(o[k]).lower() != "null":
                    return s(o[k])
    return ""


def yes(o, r):
    for k in o:
        if re.search(r, k):
            v = s(o[k])
            if v and v != "0" and v.upper() != "N" and v not in ("없음", "미설치") and v.lower() != "null":
                return True
    return False


def num(x):
    try:
        return float(x)
    except Exception:
        return None


def parse(o):
    lat = num(pick(o, [r"(?i)wgs84.*lat", "위도"], [r"(?i)lat"]))
    lon = num(pick(o, [r"(?i)wgs84.*(lot|lon|lng)", "경도"], [r"(?i)(lot|lon|lng)"], r"(?i)lotno|lotn"))
    if lat is not None and lon is not None and not (32 <= lat <= 39.5 and 124 <= lon <= 132):
        lat = lon = None
    name = pick(o, [r"(?i)(rstrm|tlt|toilet).*nm", "화장실명"], [r"(?i)nm$", r"(?i)name", "명$"]) or "공중화장실"
    addr = pick(o, [r"(?i)road.*addr", "도로명"], [r"(?i)addr", "주소"])
    lotno = pick(o, [r"(?i)lotno.*addr", "지번"], [])
    # 개방 시간: OPN_HR(예: 상시, 09:00~18:00). 기관 코드(OPN_ATMY_GRP_CD) 같은 숫자는 빼기
    opn = pick(o, [r"(?i)^opn_hr$", r"(?i)(opn|open).*(hr|tm|time)", "개방시간"], [], r"(?i)(atmy|grp|_cd$)")
    if opn.isdigit():
        opn = ""
    pid = pick(o, [r"(?i)mng.*no", "관리번호"], [r"(?i)^id$", r"(?i)_id$"]) or f"{name}_{addr or lotno}"
    flags = []
    if yes(o, r"(?i)(dspsn|disab|장애인)"): flags.append("장애인용")
    if yes(o, r"(?i)(diaper|기저귀)"): flags.append("기저귀 교환대")
    if yes(o, r"(?i)(emg|emrg|bell|비상벨)"): flags.append("비상벨")
    if yes(o, r"(?i)(cctv)"): flags.append("CCTV")
    return {"id": pid.replace("/", "_"), "name": name, "addr": addr or lotno, "lotno": lotno,
            "lat": None if lat is None else round(lat, 6), "lon": None if lon is None else round(lon, 6),
            "open": opn, "flags": flags}


# ---------- 주소 → 좌표 (카카오 로컬 API, 지난번 결과는 다시 묻지 않음) ----------

PREV_URL = "https://github.com/jonginzz33-code/toilet-memo/releases/download/public-data/public-toilets.json.gz"


def load_prev_cache():
    """지난번 파일에서 주소 → 좌표 꺼내기"""
    cache = {}
    try:
        with urllib.request.urlopen(PREV_URL, timeout=60) as r:
            d = json.loads(gzip.decompress(r.read()).decode("utf-8"))
        for it in d.get("items", []):
            for a in (it.get("addr"), it.get("lotno")):
                if a:
                    cache[a] = (it["lat"], it["lon"])
        for a, ll in d.get("miss", {}).items():
            cache[a] = None
    except Exception as e:
        print("no previous file:", e)
    return cache


def clean(a):
    a = re.sub(r"\(.*?\)", " ", a or "")
    return re.sub(r"\s+", " ", a).strip()


def kakao(query, key):
    u = "https://dapi.kakao.com/v2/local/search/address.json?" + urllib.parse.urlencode({"query": query, "size": 1})
    req = urllib.request.Request(u, headers={"Authorization": "KakaoAK " + key})
    for i in range(3):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                docs = json.loads(r.read().decode("utf-8")).get("documents", [])
            if docs:
                return round(float(docs[0]["y"]), 6), round(float(docs[0]["x"]), 6)
            return None
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                raise SystemExit(f"카카오 키 오류 HTTP {e.code}: {e.read()[:200]!r}")
            time.sleep(2 * (i + 1))
        except Exception:
            time.sleep(2 * (i + 1))
    return None


def geocode(items):
    key = os.environ.get("KAKAO_REST_KEY", "").strip()
    need = [it for it in items if it["lat"] is None]
    if not need:
        return {}
    cache = load_prev_cache()
    if not key:
        print("KAKAO_REST_KEY 없음 → 캐시에 있는 것만 씀")
    done = [0]

    def one(a):
        r = kakao(a, key)
        done[0] += 1
        if done[0] % 5000 == 0:
            print("geocoded", done[0], flush=True)
        return a, r

    # 1차: 대표 주소(도로명, 없으면 지번), 2차: 1차에서 못 찾은 것만 지번 주소
    for rnd in (1, 2):
        if rnd == 1:
            todo = {clean(it["addr"]) for it in need}
        else:
            todo = {clean(it["lotno"]) for it in need if not cache.get(clean(it["addr"]))}
        todo = sorted(a for a in todo if a and a not in cache) if key else []
        print(f"geocode round {rnd}: need {len(need)} cached {len(cache)} to ask {len(todo)}", flush=True)
        done[0] = 0
        with ThreadPoolExecutor(16) as ex:
            for a, r in ex.map(one, todo):
                cache[a] = r
    miss = {}
    for it in need:
        for a in (clean(it["addr"]), clean(it["lotno"])):
            ll = cache.get(a)
            if ll:
                it["lat"], it["lon"] = ll
                break
        else:
            a = clean(it["addr"]) or clean(it["lotno"])
            if a:
                miss[a] = None
    return miss


# ---------- 카카오 지도 '화장실' 장소로 빈 곳 채우기 (개방화장실·공원 화장실 등) ----------

def kakao_rect(rect, key, page):
    u = "https://dapi.kakao.com/v2/local/search/keyword.json?" + urllib.parse.urlencode(
        {"query": "화장실", "rect": ",".join(f"{v:.6f}" for v in rect), "page": page, "size": 15})
    req = urllib.request.Request(u, headers={"Authorization": "KakaoAK " + key})
    for i in range(4):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                raise SystemExit(f"카카오 키 오류 HTTP {e.code}")
            time.sleep(2 * (i + 1))
        except Exception:
            time.sleep(2 * (i + 1))
    return {"documents": [], "meta": {"total_count": 0, "is_end": True}}


def kakao_toilets():
    """전국을 칸으로 나눠 '화장실' 검색. 한 칸 결과가 45개를 넘으면 4칸으로 쪼갬 (카카오는 45개까지만 줌)"""
    key = os.environ.get("KAKAO_REST_KEY", "").strip()
    if not key:
        return []
    found, calls = {}, [0]

    def search(rect):
        out, page = [], 1
        while True:
            d = kakao_rect(rect, key, page); calls[0] += 1
            out += d["documents"]
            if page == 1 and d["meta"]["total_count"] > 45 and rect[2] - rect[0] > 0.002:
                return None, d["meta"]["total_count"]
            if d["meta"].get("is_end", True) or page >= 3:
                return out, 0
            page += 1

    todo = [(x / 4, y / 4, x / 4 + 0.25, y / 4 + 0.25) for x in range(124 * 4, 132 * 4) for y in range(33 * 4, 39 * 4)]
    while todo:
        nxt = []
        with ThreadPoolExecutor(8) as ex:
            for rect, (docs, _) in zip(todo, ex.map(search, todo)):
                if docs is None:
                    x1, y1, x2, y2 = rect; mx, my = (x1 + x2) / 2, (y1 + y2) / 2
                    nxt += [(x1, y1, mx, my), (mx, y1, x2, my), (x1, my, mx, y2), (mx, my, x2, y2)]
                    continue
                for d in docs:
                    if d.get("category_name", "").endswith("화장실"):
                        found[d["id"]] = d
        print("kakao rects", len(todo), "-> split", len(nxt), "found", len(found), "calls", calls[0], flush=True)
        todo = nxt
    return [{"id": "k" + d["id"], "name": d["place_name"], "addr": d.get("road_address_name") or d.get("address_name", ""),
             "lotno": d.get("address_name", ""), "lat": round(float(d["y"]), 6), "lon": round(float(d["x"]), 6),
             "open": "", "flags": []} for d in found.values()]


def add_kakao(items):
    """공공데이터에 이미 있는 곳(40m 안)은 빼고 더함"""
    grid = {}
    for it in items:
        grid.setdefault((round(it["lat"], 3), round(it["lon"], 3)), []).append(it)
    added = 0
    for k in kakao_toilets():
        dup = False
        for dy in (-0.001, 0, 0.001):
            for dx in (-0.001, 0, 0.001):
                for p in grid.get((round(k["lat"] + dy, 3), round(k["lon"] + dx, 3)), []):
                    if abs(p["lat"] - k["lat"]) * 111000 < 40 and abs(p["lon"] - k["lon"]) * 88000 < 40:
                        dup = True
        if not dup:
            items.append(k); added += 1
    print("kakao added", added)


def main(out):
    seen, items, page, total = set(), [], 1, -1
    while page <= 200:
        raw = fetch(page)
        try:
            data = json.loads(raw)
        except Exception:
            raise SystemExit("응답을 읽지 못했어요: " + raw[:300])
        if total < 0:
            total = find_total(data) or -1
        rows = find_items(data)
        if page == 1:
            print("total", total, "first item:", json.dumps(rows[0], ensure_ascii=False)[:800] if rows else raw[:300])
        if not rows:
            break
        for r in rows:
            p = parse(r)
            if p and p["id"] not in seen:
                seen.add(p["id"]); items.append(p)
        print("page", page, "items", len(items), flush=True)
        if len(rows) < ROWS or (0 < total <= page * ROWS):
            break
        page += 1
    miss = geocode(items)
    items = [it for it in items if it["lat"] is not None]
    print("with coords", len(items), "missing", len(miss))
    add_kakao(items)
    if len(items) < 1000:
        raise SystemExit(f"너무 적어요 ({len(items)}개). 저장 안 함")
    body = json.dumps({"updated": time.strftime("%Y-%m-%d"), "items": items, "miss": miss}, ensure_ascii=False, separators=(",", ":"))
    with gzip.open(out, "wt", encoding="utf-8") as f:
        f.write(body)
    print("saved", len(items))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "public-toilets.json.gz")
