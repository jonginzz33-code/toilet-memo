"""공공데이터포털 공중화장실 API를 받아 앱용 파일(public-toilets.json.gz)로 만든다.
앱의 PublicApi.parse 와 같은 규칙으로 항목을 고른다."""
import gzip, json, re, sys, time, urllib.request

KEY = "fa4fc859cd80c582d1cbf58943b918023182ed5d6e5f8d2f00b40dfdbcc0e9da"
URL = "https://apis.data.go.kr/1741000/public_restroom_info_v2/info_v2"
ROWS = 1000


def fetch(page):
    u = f"{URL}?serviceKey={KEY}&pageNo={page}&numOfRows={ROWS}&type=json&returnType=json&_type=json"
    for i in range(4):
        try:
            with urllib.request.urlopen(u, timeout=90) as r:
                return r.read().decode("utf-8")
        except Exception as e:
            print("retry", page, e, file=sys.stderr)
            time.sleep(3 * (i + 1))
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
    if lat is None or lon is None or not (32 <= lat <= 39.5) or not (124 <= lon <= 132):
        return None
    name = pick(o, [r"(?i)(rstrm|tlt|toilet).*nm", "화장실명"], [r"(?i)nm$", r"(?i)name", "명$"]) or "공중화장실"
    addr = pick(o, [r"(?i)road.*addr", "도로명"], [r"(?i)addr", "주소"])
    opn = pick(o, [r"(?i)(opn|open).*(hr|tm|time)", "개방시간"], [r"(?i)opn", "개방"])
    pid = pick(o, [r"(?i)mng.*no", "관리번호"], [r"(?i)^id$", r"(?i)_id$"]) or f"{name}_{lat:.5f}_{lon:.5f}"
    flags = []
    if yes(o, r"(?i)(dspsn|disab|장애인)"): flags.append("장애인용")
    if yes(o, r"(?i)(diaper|기저귀)"): flags.append("기저귀 교환대")
    if yes(o, r"(?i)(emg|emrg|bell|비상벨)"): flags.append("비상벨")
    if yes(o, r"(?i)(cctv)"): flags.append("CCTV")
    return {"id": pid.replace("/", "_"), "name": name, "addr": addr,
            "lat": round(lat, 6), "lon": round(lon, 6), "open": opn, "flags": flags}


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
        print("page", page, "items", len(items))
        if len(rows) < ROWS or (0 < total <= page * ROWS):
            break
        page += 1
    if len(items) < 1000:
        raise SystemExit(f"너무 적어요 ({len(items)}개). 저장 안 함")
    body = json.dumps({"updated": time.strftime("%Y-%m-%d"), "items": items}, ensure_ascii=False, separators=(",", ":"))
    with gzip.open(out, "wt", encoding="utf-8") as f:
        f.write(body)
    print("saved", len(items))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "public-toilets.json.gz")
