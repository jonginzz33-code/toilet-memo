"""주차장(전국주차장정보표준데이터) + 약국(국립중앙의료원, 요일별 영업시간) + 전기차 충전소(한국환경공단) 파일을 만든다.

- park.json.gz : 전국 주차장 한 파일 (약 2만 곳)
- ev_tile_<lat>_<lon>.json.gz : 충전소를 0.2도 칸으로 (충전기 50만 대 → 충전소 단위로 묶음)
실시간 빈 충전기는 앱이 열 때 충전소별로 따로 물어봄.
"""
import gzip, json, os, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

KEY = os.environ.get("DATA_GO_KR_KEY") or "fa4fc859cd80c582d1cbf58943b918023182ed5d6e5f8d2f00b40dfdbcc0e9da"
PARK = "https://api.data.go.kr/openapi/tn_pubr_prkplce_info_api"
EV = "https://apis.data.go.kr/B552584/EvCharger/getChargerInfo"
PHARM = "https://apis.data.go.kr/B552657/ErmctInsttInfoInqireService/getParmacyListInfoInqire"
TILE = 0.2
# 급속(DC) 충전기 종류 코드. 나머지(02 AC완속, 07 AC3상, 08 DC콤보 완속)는 완속
FAST = {"01", "03", "04", "05", "06", "09", "10"}


def get(url):
    for i in range(6):
        try:
            with urllib.request.urlopen(url, timeout=120) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:
            print("retry", url[:90], e, flush=True)
            time.sleep(3 + i * 3)
    raise SystemExit("failed: " + url[:120])


def num(v):
    try:
        return float(v)
    except Exception:
        return None


def won(v):
    v = (v or "").strip()
    return f"{int(float(v)):,}원" if v and v.replace(".", "").isdigit() and float(v) > 0 else ""


def park_row(p):
    lat, lon = num(p.get("latitude")), num(p.get("longitude"))
    if lat is None or lon is None or not (32 <= lat <= 39.5 and 124 <= lon <= 132):
        return None
    o, c = p.get("weekdayOperOpenHhmm", ""), p.get("weekdayOperColseHhmm", "")
    hours = "24시간" if (o, c) in (("00:00", "23:59"), ("00:00", "24:00")) else (f"평일 {o}~{c}" if o and c else "")
    info = (p.get("parkingchrgeInfo") or "").strip()
    fee = "무료" if info == "무료" else ""
    if not fee and won(p.get("basicCharge")):
        fee = f"{p.get('basicTime') or '?'}분 {won(p.get('basicCharge'))}"
    fee = fee or info or "요금 정보 없음"
    return [p.get("prkplceNo", ""), (p.get("prkplceNm") or "주차장").strip(), p.get("prkplceSe", ""), p.get("prkplceType", ""),
            int(num(p.get("prkcmprt")) or 0), round(lat, 6), round(lon, 6), p.get("rdnmadr") or p.get("lnmadr") or "",
            hours, fee, won(p.get("dayCmmtkt")), p.get("phoneNumber") or ""]


def parking():
    first = get(f"{PARK}?serviceKey={KEY}&pageNo=1&numOfRows=1&type=json")
    total = int(first["body"]["totalCount"])
    out = {}
    with ThreadPoolExecutor(4) as ex:
        for d in ex.map(lambda p: get(f"{PARK}?serviceKey={KEY}&pageNo={p}&numOfRows=1000&type=json"), range(1, total // 1000 + 2)):
            items = d["body"]["items"]
            for p in (items.get("item", []) if isinstance(items, dict) else items):
                r = park_row(p)
                if r:
                    out[r[0] or f"{r[1]}_{r[5]}_{r[6]}"] = r
    print("parking total", total, "kept", len(out), flush=True)
    return list(out.values())


def chargers():
    first = get(f"{EV}?serviceKey={KEY}&pageNo=1&numOfRows=1&dataType=JSON")
    total = int(first["totalCount"])
    stations = {}
    done = [0]

    def page(p):
        d = get(f"{EV}?serviceKey={KEY}&pageNo={p}&numOfRows=1000&dataType=JSON")
        done[0] += 1
        if done[0] % 100 == 0:
            print("ev pages", done[0], flush=True)
        return (d.get("items") or {}).get("item") or []

    with ThreadPoolExecutor(6) as ex:
        for items in ex.map(page, range(1, total // 1000 + 2)):
            for c in items:
                if c.get("delYn") == "Y":
                    continue
                lat, lon = num(c.get("lat")), num(c.get("lng"))
                if lat is None or lon is None or not (32 <= lat <= 39.5 and 124 <= lon <= 132):
                    continue
                s = stations.setdefault(c["statId"], [c["statId"], (c.get("statNm") or "충전소").strip(), round(lat, 6), round(lon, 6),
                                                      c.get("addr") or "", c.get("useTime") or "", c.get("busiNm") or "", 0, 0, set()])
                key = c.get("chgerId")
                if key in s[9]:
                    continue
                s[9].add(key)
                if c.get("chgerType") in FAST: s[7] += 1
                else: s[8] += 1
    print("chargers total", total, "stations", len(stations), flush=True)
    return [s[:9] for s in stations.values()]


def hhmm(v):
    """1900, "0900" → "1900". 없으면 "" """
    try:
        return f"{int(str(v).strip()):04d}"
    except Exception:
        return ""


def pharm_row(p):
    lat, lon = num(p.get("wgs84Lat")), num(p.get("wgs84Lon"))
    if lat is None or lon is None or not (32 <= lat <= 39.5 and 124 <= lon <= 132):
        return None
    # 1=월 … 7=일, 8=공휴일. "0900-1900" (닫는 시간이 2400 넘으면 다음 날 새벽까지)
    hours = []
    for d in range(1, 9):
        s, c = hhmm(p.get(f"dutyTime{d}s")), hhmm(p.get(f"dutyTime{d}c"))
        hours.append(f"{s}-{c}" if s and c else "")
    return [p.get("hpid", ""), (p.get("dutyName") or "약국").strip(), round(lat, 6), round(lon, 6),
            " ".join((p.get("dutyAddr") or "").split()), p.get("dutyTel1") or "", hours]


def pharmacies():
    first = get(f"{PHARM}?serviceKey={KEY}&pageNo=1&numOfRows=1&_type=json")
    total = int(first["response"]["body"]["totalCount"])
    out = {}
    with ThreadPoolExecutor(4) as ex:
        for d in ex.map(lambda p: get(f"{PHARM}?serviceKey={KEY}&pageNo={p}&numOfRows=1000&_type=json"), range(1, total // 1000 + 2)):
            items = d["response"]["body"].get("items") or {}
            items = items.get("item", []) if isinstance(items, dict) else items
            for p in (items if isinstance(items, list) else [items]):
                r = pharm_row(p)
                if r:
                    out[r[0] or f"{r[1]}_{r[2]}_{r[3]}"] = r
    print("pharmacies total", total, "kept", len(out), flush=True)
    return list(out.values())


def tile_of(lat, lon):
    return f"{int(lat // TILE)}_{int(lon // TILE)}"


def main(outdir):
    os.makedirs(outdir, exist_ok=True)
    pk = parking()
    if len(pk) < 5000:
        raise SystemExit(f"주차장이 너무 적어요 ({len(pk)})")
    with gzip.open(os.path.join(outdir, "park.json.gz"), "wt", encoding="utf-8") as f:
        json.dump({"updated": time.strftime("%Y-%m-%d"), "parks": pk}, f, ensure_ascii=False, separators=(",", ":"))
    ph = pharmacies()
    if len(ph) < 5000:
        raise SystemExit(f"약국이 너무 적어요 ({len(ph)})")
    with gzip.open(os.path.join(outdir, "pharm.json.gz"), "wt", encoding="utf-8") as f:
        json.dump({"updated": time.strftime("%Y-%m-%d"), "pharms": ph}, f, ensure_ascii=False, separators=(",", ":"))
    ev = chargers()
    if len(ev) < 10000:
        raise SystemExit(f"충전소가 너무 적어요 ({len(ev)})")
    tiles = {}
    for s in ev:
        tiles.setdefault(tile_of(s[2], s[3]), []).append(s)
    for t, lst in tiles.items():
        with gzip.open(os.path.join(outdir, f"ev_tile_{t}.json.gz"), "wt", encoding="utf-8") as f:
            json.dump({"updated": time.strftime("%Y-%m-%d"), "stations": lst}, f, ensure_ascii=False, separators=(",", ":"))
    print("ev tiles", len(tiles))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "place-out")
