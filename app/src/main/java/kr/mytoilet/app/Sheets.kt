package kr.mytoilet.app

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** 눌렀을 때 아래에서 올라오는 창들 */
object Sheets {

    private fun scopeChip(a: MainActivity, t: Toilet): TextView =
        if (t.scope == "group") a.chip("그룹 · ${t.groupName}", C.PURPLE_BG, C.PURPLE_TEXT)
        else a.chip("나만 보기", C.ORANGE_BG, C.ORANGE_TEXT)

    private fun distText(a: MainActivity, lat: Double, lon: Double): String {
        val la = a.myLat ?: return ""
        val lo = a.myLon ?: return ""
        return Geo.fmt(Geo.dist(la, lo, lat, lon))
    }

    // ---------------- 내 화장실 상세 ----------------

    fun toiletDetail(a: MainActivity, t: Toilet) {
        val s = Sheet(a, t.title())
        val top = a.hbox(8)
        top.add(scopeChip(a, t))
        val d = distText(a, t.lat, t.lon)
        if (d.isNotEmpty()) top.add(a.text(d, 13f, C.TEXT2))
        s.add(top)
        // 최근에 언제 갔는지(수정했는지) 맨 위에 크게
        val dateRow = a.hbox(8)
        dateRow.add(a.text("🕒 최근 수정 ${Geo.agoShort(t.updated)} · ${Geo.date(t.updated)}", 14f, C.BLUE_TEXT, 700).apply {
            background = a.round(C.BLUE_BG, 12f)
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
        }, 1f)
        // 다녀왔어요: 다른 건 그대로, 날짜만 오늘로 (서버에 올라가서 그룹 사람들도 봄)
        dateRow.add(a.btn("다녀왔어요", C.GREEN, Color.WHITE) {
            a.lifecycleScope.launch {
                a.saveToilet(t.copy(), t.scope, t.groupId, touch = true)
                a.toast("오늘 다녀온 걸로 기록했어요")
                s.dismiss()
                (a.mine + a.groupToilets).firstOrNull { it.id == t.id }?.let { toiletDetail(a, it) }
            }
        })
        s.add(dateRow)
        s.add(a.text(if (t.place.isBlank()) "위치 설명 없음" else t.place, 15f, C.TEXT2))

        val pwRow = a.hbox(10).apply {
            background = a.round(C.GRAY_BG, 14f)
            setPadding(a.dp(14), a.dp(10), a.dp(8), a.dp(10))
        }
        pwRow.add(a.text("🔑", 18f))
        pwRow.add(a.text(t.pw.ifBlank { "(비밀번호 없음)" }, 22f, C.TEXT, 700).apply {
            letterSpacing = 0.08f
            setTextIsSelectable(true)
        }, 1f)
        if (t.pw.isNotBlank()) pwRow.add(a.btn("복사", 0xFFFFFFFF.toInt()) { a.copy(t.pw) })
        s.add(pwRow)
        if (t.memo.isNotBlank()) s.add(a.text("📝 ${t.memo}", 14f, C.TEXT2))

        s.add(routeButtons(a, s, t.lat, t.lon, t.title()))
        val row = a.hbox(8)
        row.add(a.btn("수정") { s.dismiss(); edit(a, t, t.lat, t.lon) }, 1f)
        row.add(a.btn("위치 옮기기") { s.dismiss(); a.startPlacing(t, t.lat, t.lon) }, 1f)
        row.add(a.btn("삭제", C.RED_BG, C.RED_TEXT) {
            s.dismiss()
            a.confirm("삭제할까요?", "${t.title()} 화장실을 지우면 되돌릴 수 없어요.", "삭제", true) {
                a.lifecycleScope.launch { a.deleteToilet(t); a.toast("삭제했어요") }
            }
        }, 1f)
        s.add(row)
        s.show()
    }

    // ---------------- 추가 · 수정 ----------------

    fun edit(a: MainActivity, t: Toilet?, lat: Double, lon: Double) {
        val s = Sheet(a, if (t == null) "화장실 추가" else "화장실 수정")
        val (b1, building) = a.field("건물 이름 (필수)", "OO타워", t?.building ?: "")
        val (b2, floor) = a.field("층", "3층", t?.floor ?: "")
        val (b3, place) = a.field("화장실 위치 설명", "엘리베이터 내려서 오른쪽 끝, 남자화장실", t?.place ?: "")
        val (b4, pw) = a.field("비밀번호", "1234*", t?.pw ?: "")
        val (b5, memo) = a.field("메모", "휴지 없음, 밤 10시 이후 잠김 등", t?.memo ?: "", multi = true)
        s.add(b1)
        // 새로 추가할 때: 카카오 지도로 그 자리 건물·가게 이름을 찾아 추천 (누르면 건물 이름에 들어감)
        if (t == null) {
            val hint = a.text("📍 이 자리 건물 찾는 중…", 12f, C.TEXT3)
            val chips = a.hbox(6)
            val scroll = android.widget.HorizontalScrollView(a).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }
            s.add(hint)
            s.add(scroll)
            a.lifecycleScope.launch {
                val g = try { KakaoLocal.guess(lat, lon) } catch (_: Exception) { null }
                if (g == null || (g.names.isEmpty() && g.address.isBlank())) {
                    hint.visibility = android.view.View.GONE; scroll.visibility = android.view.View.GONE
                    return@launch
                }
                hint.text = if (g.address.isNotBlank()) "📍 ${g.address}" else "📍 추천 이름"
                for (n in g.names) {
                    chips.add(a.chip(n, C.BLUE_BG, C.BLUE_TEXT).apply {
                        setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7))
                        setOnClickListener { building.setText(n); building.setSelection(n.length) }
                    })
                }
                // 건물 이름이 비어 있으면 첫 번째 추천을 미리 넣어 둠
                if (building.text.isBlank() && g.names.isNotEmpty()) building.setText(g.names[0])
            }
        }
        listOf(b2, b3, b4, b5).forEach { s.add(it) }

        var scope = t?.scope ?: "mine"
        var groupId = t?.groupId ?: ""
        val canGroup = Cloud.loggedIn && a.groups.isNotEmpty()
        s.add(a.text("공개 범위", 12f, C.TEXT2, 600))
        val groupBox = a.vbox(6)
        fun fillGroups() {
            groupBox.removeAllViews()
            if (scope != "group" || a.groups.isEmpty()) return
            if (groupId.isEmpty() || a.groups.none { it.id == groupId }) groupId = a.groups.first().id
            val idx = a.groups.indexOfFirst { it.id == groupId }.coerceAtLeast(0)
            groupBox.add(a.text("어느 그룹에 공유할까요?", 12f, C.TEXT2, 600))
            groupBox.add(a.segmented(a.groups.map { it.name }, idx) { groupId = a.groups[it].id })
        }
        s.add(a.segmented(listOf("나만 보기", "그룹 공유"), if (scope == "group") 1 else 0, listOf(true, canGroup)) {
            scope = if (it == 1) "group" else "mine"; fillGroups()
        })
        if (!canGroup) s.add(a.text(if (!Cloud.loggedIn) "그룹 공유는 로그인 후 그룹을 만들면 쓸 수 있어요" else "그룹 공유는 메뉴 → 그룹에서 그룹을 먼저 만들어 주세요", 12f, C.TEXT3))
        s.add(groupBox)
        fillGroups()

        val err = a.text("", 13f, C.RED_TEXT)
        s.add(err)
        val row = a.hbox(8)
        row.add(a.btn("취소") { s.dismiss() }, 1f)
        row.add(a.primaryBtn("저장") {
            val bn = building.text.toString().trim()
            if (bn.isEmpty()) { err.text = "건물 이름을 적어 주세요"; return@primaryBtn }
            val oldScope = t?.scope
            val oldGroup = t?.groupId
            val item = t?.copy() ?: Toilet(lat = lat, lon = lon)
            item.building = bn
            item.floor = floor.text.toString().trim()
            item.place = place.text.toString().trim()
            item.pw = pw.text.toString().trim()
            item.memo = memo.text.toString().trim()
            item.scope = scope
            item.groupId = if (scope == "group") groupId else ""
            item.groupName = a.groups.firstOrNull { it.id == item.groupId }?.name ?: ""
            s.dismiss()
            a.lifecycleScope.launch {
                a.saveToilet(item, oldScope, oldGroup)
                a.toast("저장했어요")
            }
        }, 1f)
        s.add(row)
        s.show()
    }

    // ---------------- 목록 ----------------

    // ---------------- 가입자 목록 (총관리자) ----------------

    fun users(a: MainActivity) {
        val s = Sheet(a, "👤 가입자", half = true)
        val box = a.vbox(8)
        s.add(box)
        box.add(a.text("불러오는 중…", 13f, C.TEXT3))
        s.show()
        a.lifecycleScope.launch {
            val list = try { Cloud.allUsers() } catch (e: Exception) { box.removeAllViews(); box.add(a.text("불러오지 못했어요: ${e.message}", 13f, C.RED_TEXT)); return@launch }
            val groups = try { Cloud.allGroups() } catch (_: Exception) { emptyList() }
            box.removeAllViews()
            val now = System.currentTimeMillis()
            val today = list.count { now - it.lastSeen < Cloud.DAY }
            val week = list.count { now - it.lastSeen < 7 * Cloud.DAY }
            box.add(a.text("전체 ${list.size}명 · 오늘 접속 ${today}명 · 7일 안 접속 ${week}명", 13f, C.TEXT2, 700))
            for (u in list) {
                val r = a.vbox(2).apply { background = a.round(0xFFFFFFFF.toInt(), 12f); setPadding(a.dp(12), a.dp(9), a.dp(12), a.dp(9)) }
                val head = a.hbox(6)
                head.add(a.text(u.name, 15f, C.TEXT, 700), 1f)
                if (u.uid == Cloud.uid) head.add(a.chip("나", C.BLUE_BG, C.BLUE_TEXT))
                if (now - u.lastSeen < Cloud.DAY) head.add(a.chip("오늘 접속", C.GREEN_BG, C.GREEN_TEXT))
                r.add(head)
                val gs = groups.filter { u.uid in it.members }.joinToString(", ") { it.name + if (it.owner == u.uid) "(방장)" else "" }
                r.add(a.text(
                    "가입 ${if (u.created > 0) Geo.date(u.created) else "-"} · 최근 접속 ${if (u.lastSeen > 0) Geo.ago(u.lastSeen) else "-"}" +
                        if (gs.isNotBlank()) "\n그룹: $gs" else "",
                    12f, C.TEXT3))
                box.add(r)
            }
        }
    }

    // ---------------- 그룹 관리 (방장·부방장) ----------------

    fun groupManage(a: MainActivity, g0: Group) {
        val s = Sheet(a, "👥 ${g0.name}", half = true)
        val box = a.vbox(8)
        s.add(box)
        fun reload() {
            a.lifecycleScope.launch {
                val me = Cloud.uid
                val g = try { Cloud.myGroups().firstOrNull { it.id == g0.id } } catch (_: Exception) { null }
                box.removeAllViews()
                if (g == null) { box.add(a.text("그룹을 찾을 수 없어요", 14f, C.TEXT2)); return@launch }
                val cfg = Cloud.groupConfig()
                val isOwner = g.isOwner(me)
                box.add(a.text("${g.members.size} / ${g.limit(cfg.defaultMax)}명", 14f, C.PURPLE_TEXT, 800))

                // 초대 코드
                val codeCard = a.vbox(4).apply { background = a.round(0xFFFFFFFF.toInt(), 14f); setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10)) }
                val cr = a.hbox(6)
                cr.add(a.text("초대 코드  ${g.code}", 15f, C.TEXT, 800).apply { letterSpacing = 0.1f }, 1f)
                cr.add(a.btn("복사") { a.copy(g.code) })
                if (isOwner) cr.add(a.btn("새 코드", C.RED_BG, C.RED_TEXT) {
                    a.confirm("새 코드를 만들까요?", "예전 코드 ${g.code}는 바로 막혀요.", "새 코드") {
                        a.lifecycleScope.launch { try { val c = Cloud.newGroupCode(g.id); a.toast("새 코드: $c"); reload() } catch (e: Exception) { a.toast("실패: ${e.message}") } }
                    }
                })
                codeCard.add(cr)
                if (isOwner) codeCard.add(a.text("새 코드를 만들면 예전 코드는 바로 막혀요 (방장만)", 11f, C.TEXT3))
                box.add(codeCard)

                // 가입 요청
                val reqs = try { Cloud.joinRequests(g.id) } catch (_: Exception) { emptyList() }
                box.add(a.text("🙋 가입 요청 ${reqs.size}", 13f, C.TEXT2, 800))
                if (reqs.isEmpty()) box.add(a.text("새 요청이 없어요", 12f, C.TEXT3))
                for (r in reqs) {
                    val rr = a.hbox(6).apply { background = a.round(0xFFFFFFFF.toInt(), 12f); setPadding(a.dp(12), a.dp(8), a.dp(8), a.dp(8)) }
                    rr.add(a.text(r.name, 14f, C.TEXT, 700), 1f)
                    rr.add(a.btn("수락", C.GREEN, Color.WHITE) {
                        a.lifecycleScope.launch {
                            val err = try { Cloud.acceptJoin(g, r) } catch (e: Exception) { "실패: ${e.message}" }
                            a.toast(err ?: "${r.name}님을 받았어요"); reload(); a.reloadMine()
                        }
                    })
                    rr.add(a.btn("거절") { a.lifecycleScope.launch { try { Cloud.rejectJoin(g.id, r.uid) } catch (_: Exception) {}; reload() } })
                    box.add(rr)
                }

                // 멤버
                box.add(a.text("멤버 ${g.members.size}", 13f, C.TEXT2, 800))
                val ordered = listOf(g.owner) + g.subs.filter { it != g.owner } + g.members.filter { it != g.owner && it !in g.subs }
                for (u in ordered.filter { it in g.members }) {
                    val mr = a.hbox(6).apply { background = a.round(0xFFFFFFFF.toInt(), 12f); setPadding(a.dp(12), a.dp(8), a.dp(8), a.dp(8)) }
                    mr.add(a.text(g.nameOf(u) + if (u == me) " (나)" else "", 14f, C.TEXT, 700), 1f)
                    when {
                        u == g.owner -> mr.add(a.chip("👑 방장", C.AMBER_BG, C.AMBER_TEXT))
                        u in g.subs -> mr.add(a.chip("⭐ 부방장", C.PURPLE_BG, C.PURPLE_TEXT))
                    }
                    if (isOwner && u != g.owner) {
                        val sub = u in g.subs
                        mr.add(a.btn(if (sub) "해제" else "부방장 지정") {
                            a.lifecycleScope.launch { try { Cloud.setSub(g.id, u, !sub); reload() } catch (e: Exception) { a.toast("실패: ${e.message}") } }
                        })
                    }
                    // 방장은 누구나(본인 빼고), 부방장은 일반 멤버만 내보내기
                    val canKick = u != me && u != g.owner && (isOwner || (g.isSub(me) && u !in g.subs))
                    if (canKick) mr.add(a.btn("내보내기", C.RED_BG, C.RED_TEXT) {
                        a.confirm("내보낼까요?", "${g.nameOf(u)}님을 '${g.name}' 그룹에서 내보내요.", "내보내기", true) {
                            a.lifecycleScope.launch { try { Cloud.kick(g.id, u); reload(); a.reloadMine() } catch (e: Exception) { a.toast("실패: ${e.message}") } }
                        }
                    })
                    box.add(mr)
                }
            }
        }
        s.show()
        reload()
    }

    // ---------------- 제휴 가게 ----------------

    private fun photoView(a: MainActivity, b: android.graphics.Bitmap?, sizeDp: Int): View =
        if (b != null) android.widget.ImageView(a).apply {
            setImageBitmap(b)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = a.round(0xFFFFFFFF.toInt(), 14f)
            layoutParams = LinearLayout.LayoutParams(a.dp(sizeDp), a.dp(sizeDp))
        } else a.text("🤝", (sizeDp / 2.2f), C.TEXT, 400, Gravity.CENTER).apply {
            background = a.round(C.GREEN_BG, 14f)
            layoutParams = LinearLayout.LayoutParams(a.dp(sizeDp), a.dp(sizeDp))
        }

    fun partnerDetail(a: MainActivity, p: Partner) {
        Partners.count(p.id, Partners.OPEN)
        val s = Sheet(a, null)
        val (mlat, mlon) = a.myLat?.let { it to (a.myLon ?: 0.0) } ?: (null to null)
        val dist = if (mlat != null && mlon != null) Geo.dist(mlat, mlon, p.lat, p.lon) else null

        val head = a.hbox(12)
        head.addView(photoView(a, p.bitmap(), 58))
        val ht = a.vbox(4)
        ht.add(a.text(p.name, 20f, C.TEXT, 700))
        val chips = a.hbox(6)
        chips.add(a.chip("🤝 제휴 개방화장실", C.GREEN_BG, C.GREEN_TEXT))
        if (dist != null) chips.add(a.text(Geo.fmt(dist), 13f, C.TEXT2))
        ht.add(chips)
        head.add(ht, 1f)
        s.add(head)

        if (p.rule.isNotBlank()) s.add(a.text("📢 ${p.rule}", 15f, C.AMBER_TEXT, 800).apply {
            background = a.round(C.AMBER_BG, 12f); setPadding(a.dp(12), a.dp(9), a.dp(12), a.dp(9))
        })

        // 비밀번호 칸
        val pwText: String
        var pwSub: String? = null
        when {
            p.pwMode == "staff" || p.pw.isBlank() -> { pwText = "🙋 직원에게 말씀해 주세요"; if (p.pwMode == "staff") pwSub = "이 가게는 비밀번호 대신 직원이 안내해요" }
            p.pwNear && (dist == null || dist > 50) -> {
                pwText = "🔒 비밀번호는 매장 50m 안에서 보여요"
                pwSub = if (dist == null) "내 위치를 찾으면 확인할 수 있어요" else "지금 ${Geo.fmt(dist)} · 가까이 가서 다시 열어 주세요"
            }
            else -> pwText = "🔑 ${p.pw}"
        }
        val pwBox = a.vbox(2).apply { background = a.round(C.GRAY_BG, 14f); setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10)) }
        pwBox.add(a.text(pwText, if (pwText.startsWith("🔑")) 22f else 17f, C.TEXT, 800).apply { setTextIsSelectable(true) })
        pwSub?.let { pwBox.add(a.text(it, 12f, C.TEXT3)) }
        s.add(pwBox)

        val info = listOf(p.place.takeIf { it.isNotBlank() }?.let { "📍 $it" }, p.open.takeIf { it.isNotBlank() }?.let { "🕒 $it" }).filterNotNull()
        if (info.isNotEmpty()) s.add(a.text(info.joinToString("  ·  "), 14f, C.TEXT2))

        // 쿠폰 (체크한 가게만)
        if (p.coupon && p.couponText.isNotBlank()) {
            s.add(a.text("🎁 ${p.couponText}", 15f, C.AMBER_TEXT, 800).apply {
                background = a.round(C.AMBER_BG, 14f, 2, C.AMBER); setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            })
            s.add(a.btn("🎁 쿠폰 보여 주기", C.AMBER, Color.WHITE) { Partners.count(p.id, Partners.COUPON); showCoupon(a, p) })
        }

        val rr = a.hbox(8)
        rr.add(a.btn("🚗 차량 길찾기", C.CAR, Color.WHITE) { Partners.count(p.id, Partners.ROUTE); s.dismiss(); a.showRoute(p.lat, p.lon, p.name, car = true) }, 1f)
        rr.add(a.btn("🚶 도보 길찾기", C.BLUE, Color.WHITE) { Partners.count(p.id, Partners.ROUTE); s.dismiss(); a.showRoute(p.lat, p.lon, p.name, car = false) }, 1f)
        s.add(rr)

        val vr = a.hbox(8)
        vr.add(a.btn("다녀왔어요", C.GREEN, Color.WHITE) {
            Login.need(a, "다녀왔다고 알리려면") {
                a.lifecycleScope.launch {
                    try { Cloud.visit(p.statusId); Partners.count(p.id, Partners.VISIT); a.toast("고마워요! 다른 사람들도 볼 수 있어요") } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }
        }, 1f)
        vr.add(a.btn("⛔ 사용불가 알리기", C.RED_BG, C.RED_TEXT) {
            Login.need(a, "사용불가를 알리려면") { s.dismiss(); reportUnavailable(a, PublicToilet(p.statusId, p.name, p.addr, p.lat, p.lon)) }
        }, 1f)
        s.add(vr)

        if (a.isAdmin) {
            val ar = a.hbox(8)
            ar.add(a.btn("수정") { s.dismiss(); partnerEdit(a, p.copy()) }, 1f)
            ar.add(a.btn("📊 통계") { partnerStats(a, p) }, 1f)
            ar.add(a.btn("삭제", C.RED_BG, C.RED_TEXT) {
                s.dismiss()
                a.confirm("삭제할까요?", "${p.name} 제휴 가게를 지도에서 지워요.", "삭제", true) {
                    a.lifecycleScope.launch {
                        try { Partners.delete(p); a.reloadPartners(); a.toast("삭제했어요") } catch (e: Exception) { a.toast("실패: ${e.message}") }
                    }
                }
            }, 1f)
            s.add(ar)
        }
        s.show()
    }

    /** 화면 가득 쿠폰 (직원에게 보여 주기) */
    private fun showCoupon(a: MainActivity, p: Partner) {
        val d = android.app.Dialog(a, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        val root = a.vbox(16).apply {
            gravity = Gravity.CENTER
            setPadding(a.dp(28), a.dp(28), a.dp(28), a.dp(28))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFFF4D6.toInt(), 0xFFFDE3B0.toInt()))
        }
        root.addView(photoView(a, p.bitmap(), 96).apply { (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
        root.add(a.text(p.name, 22f, C.TEXT, 800, Gravity.CENTER))
        root.add(a.text("🎁 ${p.couponText}", 26f, C.AMBER_TEXT, 800, Gravity.CENTER).apply {
            background = a.round(0xFFFFFFFF.toInt(), 18f, 3, C.AMBER); setPadding(a.dp(18), a.dp(22), a.dp(18), a.dp(22))
        })
        root.add(a.text("나만의 화장실 앱 쿠폰 · ${Geo.date(System.currentTimeMillis())}", 13f, C.TEXT2, 600, Gravity.CENTER))
        root.add(a.btn("닫기") { d.dismiss() })
        d.setContentView(root)
        d.show()
    }

    /** 관리자: 제휴 가게 등록·수정 (위치는 이미 핀으로 정해진 상태) */
    fun partnerEdit(a: MainActivity, p: Partner) {
        val isNew = a.partners.none { it.id == p.id }
        val s = Sheet(a, if (isNew) "🤝 제휴 가게 등록" else "🤝 제휴 가게 수정")
        val (b1, name) = a.field("가게 이름 (필수)", "***** 안양점", p.name)
        s.add(b1)

        // 사진
        s.add(a.text("🖼 가게 사진 (핀에 보여요)", 12f, C.TEXT2, 600))
        val photoRow = a.hbox(10)
        var img = p.img
        val holder = FrameLayout(a)
        fun showPhoto() { holder.removeAllViews(); holder.addView(photoView(a, Partners.decode(img), 64)) }
        showPhoto()
        photoRow.addView(holder)
        val pv = a.vbox(4)
        pv.add(a.btn("📷 사진 고르기", C.BLUE, Color.WHITE) {
            a.pickPhoto { uri ->
                if (uri == null) return@pickPhoto
                val b = Partners.loadPhoto(a, uri)
                if (b == null) a.toast("사진을 불러오지 못했어요") else { img = b; showPhoto() }
            }
        })
        pv.add(a.text("가운데를 동그랗게 잘라 작게 줄여서 저장해요", 11f, C.TEXT3))
        photoRow.add(pv, 1f)
        s.add(photoRow)

        // 위치
        val locText = a.text("📍 확인 중…", 13f, C.BLUE_TEXT)
        s.add(locText)
        if (p.addr.isNotBlank()) locText.text = "📍 ${p.addr}"
        a.lifecycleScope.launch {
            val g = try { KakaoLocal.guess(p.lat, p.lon) } catch (_: Exception) { null }
            if (g != null && g.address.isNotBlank()) { p.addr = g.address; locText.text = "📍 ${g.address}" }
            else if (p.addr.isBlank()) locText.text = "📍 %.5f, %.5f".format(p.lat, p.lon)
        }

        val (b2, place) = a.field("화장실 위치 설명", "매장 안쪽 왼쪽 문", p.place)
        val (b3, open) = a.field("개방 시간", "07:00~22:00", p.open)
        val (b4, rule) = a.field("이용 규칙 (맨 위에 크게 보여요)", "구매 고객 전용", p.rule)
        listOf(b2, b3, b4).forEach { s.add(it) }

        // 비밀번호 칸
        s.add(a.text("비밀번호 칸", 12f, C.TEXT2, 600))
        var pwMode = p.pwMode
        val pwBox = a.vbox(8)
        val (b5, pw) = a.field("비밀번호", "1234*", p.pw)
        var pwNear = p.pwNear
        pwBox.add(b5)
        pwBox.add(a.toggleRow("🔒 번호는 매장 50m 안에서만 보이기", pwNear, "멀리서 캡처해서 퍼뜨리는 걸 막아요") { pwNear = it })
        val staffNote = a.text("상세 창 비밀번호 칸에 '🙋 직원에게 말씀해 주세요'가 크게 나와요", 12f, C.TEXT3)
        fun paintPw() {
            pwBox.visibility = if (pwMode == "pw") View.VISIBLE else View.GONE
            staffNote.visibility = if (pwMode == "staff") View.VISIBLE else View.GONE
        }
        s.add(a.segmented(listOf("번호 입력", "🙋 직원에게 문의"), if (pwMode == "pw") 0 else 1) { pwMode = if (it == 0) "pw" else "staff"; paintPw() })
        s.add(staffNote)
        s.add(pwBox)
        paintPw()

        // 쿠폰
        var coupon = p.coupon
        val (b6, couponText) = a.field("🎁 혜택 쿠폰 문구", "앱 화면 보여 주면 음료 500원 할인", p.couponText)
        s.add(a.toggleRow("🎁 쿠폰 있음", coupon, "끄면 쿠폰 칸이 아예 안 보여요") { coupon = it; b6.visibility = if (it) View.VISIBLE else View.GONE })
        s.add(b6)
        b6.visibility = if (coupon) View.VISIBLE else View.GONE

        val err = a.text("", 13f, C.RED_TEXT)
        s.add(err)
        val row = a.hbox(8)
        row.add(a.btn("📍 위치 다시 정하기") {
            p.name = name.text.toString().trim(); p.img = img; p.place = place.text.toString().trim()
            p.open = open.text.toString().trim(); p.rule = rule.text.toString().trim(); p.pwMode = pwMode
            p.pw = pw.text.toString().trim(); p.pwNear = pwNear; p.coupon = coupon; p.couponText = couponText.text.toString().trim()
            s.dismiss(); a.startPartnerPlacing(p)
        }, 1f)
        row.add(a.btn(if (isNew) "등록하기" else "저장", C.GREEN, Color.WHITE) {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { err.text = "가게 이름을 적어 주세요"; return@btn }
            if (coupon && couponText.text.isBlank()) { err.text = "쿠폰 문구를 적거나 '쿠폰 있음'을 꺼 주세요"; return@btn }
            p.name = n; p.img = img; p.place = place.text.toString().trim(); p.open = open.text.toString().trim()
            p.rule = rule.text.toString().trim(); p.pwMode = pwMode; p.pw = pw.text.toString().trim(); p.pwNear = pwNear
            p.coupon = coupon; p.couponText = if (coupon) couponText.text.toString().trim() else ""
            s.dismiss()
            a.lifecycleScope.launch {
                a.busy(true)
                try { Partners.save(p); a.reloadPartners(); a.toast(if (isNew) "제휴 가게를 등록했어요" else "저장했어요") }
                catch (e: Exception) { a.toast("저장하지 못했어요: ${e.message}") }
                finally { a.busy(false) }
            }
        }, 1f)
        s.add(row)
        s.show()
    }

    /** 관리자: 가게별 이번 달 통계 + 사장님께 보낼 이미지 */
    fun partnerStats(a: MainActivity, p: Partner) {
        val s = Sheet(a, "📊 ${p.name}")
        val card = a.vbox(8).apply { background = a.round(0xFFFFFFFF.toInt(), 16f); setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14)) }
        val month = java.text.SimpleDateFormat("yyyy년 M월", java.util.Locale.KOREA).format(java.util.Date())
        card.add(a.text("${p.name} · $month", 15f, C.TEXT, 800))
        val body = a.vbox(8)
        body.add(a.text("불러오는 중…", 13f, C.TEXT3))
        card.add(body)
        s.add(card)
        s.add(a.btn("📤 사장님께 보낼 이미지로 저장·공유", C.GREEN, Color.WHITE) { shareView(a, card, "partner_stats.png") })
        s.add(a.text("지도에 보임은 앱을 켤 때마다 가게당 한 번씩 세요. 사용자 폰에서 모았다가 한 번에 올려서 조금 늦게 반영될 수 있어요.", 11f, C.TEXT3))
        s.show()
        a.lifecycleScope.launch {
            val st = try { Partners.stats(p.id) } catch (e: Exception) { body.removeAllViews(); body.add(a.text("불러오지 못했어요: ${e.message}", 13f, C.RED_TEXT)); return@launch }
            body.removeAllViews()
            val rows = listOf(
                "지도에 보임" to Partners.VIEW, "상세 열어 봄" to Partners.OPEN, "길찾기" to Partners.ROUTE,
                "다녀왔어요" to Partners.VISIT
            ) + (if (p.coupon) listOf("🎁 쿠폰 보여 줌" to Partners.COUPON) else emptyList())
            val maxV = (rows.maxOfOrNull { st[it.second] ?: 0L } ?: 0L).coerceAtLeast(1L)
            for ((label, key) in rows) {
                val v = st[key] ?: 0L
                val r = a.hbox(8)
                r.add(a.text(label, 13f, C.TEXT2).apply { minWidth = a.dp(92) })
                val bar = View(a).apply { background = a.round(C.GREEN, 5f) }
                r.addView(bar, LinearLayout.LayoutParams((a.dp(140) * v / maxV).toInt().coerceAtLeast(a.dp(4)), a.dp(14)))
                r.add(a.text("%,d".format(v), 14f, C.TEXT, 800))
                body.add(r)
            }
            val routes = st[Partners.ROUTE] ?: 0L
            val coupons = st[Partners.COUPON] ?: 0L
            body.add(a.text("💡 이번 달 이 앱으로 ${routes}명이 길찾기로 매장을 찾았어요" + if (p.coupon) ", ${coupons}명이 쿠폰을 보여 줬어요." else ".", 13f, C.TEXT2).apply {
                background = a.round(C.GRAY_BG, 12f); setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8))
            })
        }
    }

    /** 화면 일부를 그림으로 만들어 공유 (카톡 등) */
    private fun shareView(a: MainActivity, v: View, name: String) {
        try {
            val b = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            c.drawColor(Color.WHITE)
            v.draw(c)
            val dir = java.io.File(a.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val f = java.io.File(dir, name)
            f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(a, "${a.packageName}.files", f)
            a.startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "통계 보내기"))
        } catch (e: Exception) {
            a.toast("이미지를 만들지 못했어요: ${e.message}")
        }
    }

    /** 🚗 차량 길찾기 / 🚶 도보 길찾기 */
    private fun routeButtons(a: MainActivity, s: Sheet, lat: Double, lon: Double, name: String): View {
        val r = a.hbox(8)
        r.add(a.btn("🚗 차량 길찾기", C.CAR, Color.WHITE) { s.dismiss(); a.showRoute(lat, lon, name, car = true) }, 1f)
        r.add(a.btn("🚶 도보 길찾기", C.BLUE, Color.WHITE) { s.dismiss(); a.showRoute(lat, lon, name, car = false) }, 1f)
        return r
    }

    /** 내가 올린 화장실 모음 (나만 보기 + 내가 그룹에 올린 것). 줄마다 그룹 공유 스위치 */
    fun myToilets(a: MainActivity) {
        val s = Sheet(a, "📁 나의 화장실", half = true)
        val box = a.vbox(8)
        s.add(box)

        fun fill() {
            box.removeAllViews()
            val list = (a.mine + a.groupToilets.filter { it.by.isNotEmpty() && it.by == Cloud.uid })
                .distinctBy { it.id }.sortedByDescending { it.updated }
            val canGroup = Cloud.loggedIn && a.groups.isNotEmpty()
            box.add(a.text(
                "${list.size}곳 · 스위치를 켜면 그룹에 공유돼요" +
                    if (!canGroup) "\n(그룹 공유는 로그인 후 그룹을 만들면 쓸 수 있어요)" else "",
                12f, C.TEXT3))
            if (list.isEmpty()) box.add(a.text("아직 올린 화장실이 없어요.\n맨 위 '＋ 화장실 추가'로 저장해 보세요.", 15f, C.TEXT2))
            for (t in list) {
                val row = a.hbox(10).apply {
                    background = a.round(0xFFFFFFFF.toInt(), 14f)
                    setPadding(a.dp(12), a.dp(10), a.dp(8), a.dp(10))
                }
                val tx = a.vbox(2)
                tx.add(a.text(t.title(), 15f, C.TEXT, 700))
                val where = if (t.scope == "group") "👥 '${t.groupName.ifBlank { a.groups.firstOrNull { it.id == t.groupId }?.name ?: "그룹" }}' 그룹에 공유 중" else "🔒 나만 보기"
                tx.add(a.text("${Geo.agoShort(t.updated)} · $where", 12f, C.TEXT2))
                tx.setOnClickListener { s.dismiss(); toiletDetail(a, t) }
                row.add(tx, 1f)
                row.add(a.text("그룹\n공지", 11f, C.TEXT3, 600, Gravity.CENTER))
                val sw = com.google.android.material.materialswitch.MaterialSwitch(a).apply {
                    isChecked = t.scope == "group"
                    isEnabled = canGroup || t.scope == "group"
                }
                sw.setOnCheckedChangeListener { v, on ->
                    fun apply(groupId: String) {
                        val old = t.scope to t.groupId
                        val item = t.copy()
                        item.scope = if (on) "group" else "mine"
                        item.groupId = if (on) groupId else ""
                        item.groupName = a.groups.firstOrNull { it.id == groupId }?.name ?: ""
                        a.lifecycleScope.launch {
                            a.saveToilet(item, old.first, old.second, touch = false)
                            a.toast(if (on) "${item.groupName} 그룹에 공유했어요" else "나만 보기로 바꿨어요")
                            fill()
                        }
                    }
                    if (!on) { apply(""); return@setOnCheckedChangeListener }
                    if (a.groups.size == 1) { apply(a.groups[0].id); return@setOnCheckedChangeListener }
                    // 그룹이 여러 개면 고르기
                    var picked = false
                    androidx.appcompat.app.AlertDialog.Builder(a)
                        .setTitle("어느 그룹에 공유할까요?")
                        .setItems(a.groups.map { it.name }.toTypedArray()) { _, i -> picked = true; apply(a.groups[i].id) }
                        .setOnDismissListener { if (!picked) v.isChecked = false }
                        .show()
                }
                row.add(sw)
                box.add(row)
            }
        }
        fill()
        s.show()
    }

    /** 가까운 순 목록: 내 화장실 + 그룹 화장실 + 공중화장실 (반경 2km) */
    fun list(a: MainActivity) {
        // 기준: 내 위치. 단, 검색 등으로 지도를 멀리(500m 넘게) 옮겼으면 지도 가운데
        val (mlat, mlon) = a.center()
        val me = a.myLat?.let { it to (a.myLon ?: 0.0) }
        val useMe = me != null && Geo.dist(me.first, me.second, mlat, mlon) <= 500
        val (clat, clon) = if (useMe) me!! else (mlat to mlon)
        val s = Sheet(a, if (useMe) "가까운 화장실 (내 위치 기준)" else "가까운 화장실 (지도 가운데 기준)", half = true)
        val box = a.vbox(8)
        val loading = a.text("공중화장실 찾는 중…", 13f, C.TEXT3)
        s.add(box)
        s.add(loading)

        fun row(title: String, sub: String, chip: View, onClick: () -> Unit) = a.hbox(10).apply {
            background = a.round(0xFFFFFFFF.toInt(), 14f)
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            val tx = a.vbox(2)
            tx.add(a.text(title, 15f, C.TEXT, 700))
            tx.add(a.text(sub, 12f, C.TEXT2))
            add(tx, 1f)
            add(chip)
            setOnClickListener { s.dismiss(); onClick() }
        }

        fun fill(publics: List<PublicToilet>) {
            box.removeAllViews()
            val items: List<Pair<Any, Double>> =
                (((if (a.showMine) a.mine else emptyList()) + (if (a.showGroup) a.groupToilets else emptyList())).map { it as Any to Geo.dist(clat, clon, it.lat, it.lon) } +
                    publics.map { it as Any to Geo.dist(clat, clon, it.lat, it.lon) }.filter { it.second <= 2000 } +
                    (if (a.showPartner) a.partners else emptyList()).map { it as Any to Geo.dist(clat, clon, it.lat, it.lon) }.filter { it.second <= 2000 })
                    .sortedBy { it.second }
            if (items.isEmpty()) box.add(a.text("근처에 화장실이 없어요.\n맨 위 '＋ 화장실 추가'로 저장해 보세요.", 15f, C.TEXT2))
            for ((x, d) in items.take(80)) {
                when (x) {
                    is Toilet -> box.add(row(x.title(), "${Geo.fmt(d)} · ${Geo.agoShort(x.updated)} · ${x.place.ifBlank { "위치 설명 없음" }}", scopeChip(a, x)) { toiletDetail(a, x) })
                    is Partner -> box.add(row(x.name, "${Geo.fmt(d)} · ${x.rule.ifBlank { x.addr }}", a.chip("🤝 제휴", C.GREEN_BG, C.GREEN_TEXT)) { partnerDetail(a, x) })
                    is PublicToilet -> box.add(row(x.name, "${Geo.fmt(d)} · ${x.addr.ifBlank { "공중화장실" }}", a.chip("공중화장실", C.GRAY_BG, C.TEXT2)) { publicDetail(a, x) })
                }
            }
        }

        fill(emptyList())
        s.show()
        if (!a.prefs.showPublic) { loading.visibility = View.GONE; return }
        a.lifecycleScope.launch {
            val pubs = ArrayList<PublicToilet>()
            try {
                for (c in Geo.cellsAround(clat, clon, 2000.0)) pubs.addAll(Cloud.publicCell(c))
            } catch (_: Exception) {}
            loading.visibility = View.GONE
            fill(pubs.distinctBy { it.id })
        }
    }

    // ---------------- 공중화장실 상세 ----------------

    fun publicDetail(a: MainActivity, p: PublicToilet) {
        val s = Sheet(a, p.name)
        val info = listOf(distText(a, p.lat, p.lon), p.open.let { if (it.isBlank()) "" else "개방 $it" }).filter { it.isNotBlank() }
        s.add(a.text(info.joinToString(" · ").ifBlank { "공중화장실" }, 14f, C.TEXT2))
        if (p.addr.isNotBlank()) s.add(a.text(p.addr, 13f, C.TEXT3))
        val visitRow = a.hbox(8)
        val visitText = a.text("🕒 최근 다녀옴: 확인 중…", 14f, C.BLUE_TEXT, 700).apply {
            background = a.round(C.BLUE_BG, 12f)
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
        }
        fun showVisit(at: Long) {
            visitText.text = if (at <= 0) "🕒 최근 다녀옴: 기록 없음" else "🕒 최근 다녀옴: ${Geo.agoShort(at)} · ${Geo.date(at)}"
        }
        visitRow.add(visitText, 1f)
        visitRow.add(a.btn("다녀왔어요", C.GREEN, Color.WHITE) {
            Login.need(a, "다녀왔다고 알리려면") {
                a.lifecycleScope.launch {
                    try { Cloud.visit(p.id); showVisit(System.currentTimeMillis()); a.toast("고마워요! 다른 사람들도 볼 수 있어요") }
                    catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }
        })
        s.add(visitRow)
        a.lifecycleScope.launch { showVisit(Cloud.lastVisit(p.id)) }
        if (p.flags.isNotEmpty()) {
            val fr = a.hbox(6)
            p.flags.forEach { fr.add(a.chip(it, C.GRAY_BG, C.TEXT2)) }
            s.add(fr)
        }

        // 사용불가
        val un = a.unavail[p.id]
        if (un != null && un.count > 0) {
            val box = a.vbox(2).apply {
                background = a.round(C.RED, 14f)
                setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
            }
            box.add(a.text("⛔ 사용불가 신고 ${un.count}건", 16f, Color.WHITE, 700))
            box.add(a.text("최근 신고 ${Geo.ago(un.last)} · 사유: " + un.reasons.entries.joinToString(", ") { "${it.key} ${it.value}" }, 12f, Color.WHITE))
            s.add(box)
        }
        s.add(routeButtons(a, s, p.lat, p.lon, p.name))
        val ur = a.hbox(8)
        ur.add(a.btn("⛔ 사용불가 알리기", C.RED_BG, C.RED_TEXT, 0xFFF09595.toInt()) {
            Login.need(a, "사용불가를 알리려면") { s.dismiss(); reportUnavailable(a, p) }
        }, 1f)
        ur.add(a.btn("✅ 다시 열렸어요", C.GREEN_BG, C.GREEN_TEXT, 0xFF5DCAA5.toInt()) {
            Login.need(a, "다시 열렸다고 알리려면") {
                a.lifecycleScope.launch {
                    try { Cloud.reopen(p.id); a.refreshUnavailable(); a.toast("알려 줘서 고마워요") } catch (e: Exception) { a.toast("실패: ${e.message}") }
                    s.dismiss()
                }
            }
        }, 1f)
        s.add(ur)

        val ratingBox = a.vbox(8)
        val memoBox = a.vbox(8)
        s.add(ratingBox)
        s.add(memoBox)
        s.show()

        a.lifecycleScope.launch {
            a.busy(true)
            try {
                val v = Cloud.votes(p.id)
                val myTags = if (Cloud.loggedIn) Cloud.myVoteTags(p.id) else emptyList()
                fillRating(a, ratingBox, p, v, myTags)
                fillMemos(a, memoBox, p, Cloud.memos(p.id))
            } catch (e: Exception) {
                ratingBox.add(a.text("평점·메모를 불러오지 못했어요", 13f, C.TEXT3))
            } finally {
                a.busy(false)
            }
        }
    }

    private fun fillRating(a: MainActivity, box: LinearLayout, p: PublicToilet, v: Cloud.Votes, myTags: List<String>) {
        box.removeAllViews()
        val head = a.hbox(8)
        head.add(a.text(if (v.total == 0) "★ -" else "★ %.1f".format(v.score), 26f, C.TEXT, 700))
        head.add(a.text("깨끗함 · ${v.total}명 · 최근 60일", 13f, C.TEXT2))
        box.add(head)
        if (v.total > 0) {
            val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(v.clean to C.GREEN, v.normal to C.AMBER, v.bad to C.RED).forEach { (n, col) ->
                if (n > 0) bar.addView(View(a).apply { setBackgroundColor(col) }, LinearLayout.LayoutParams(0, a.dp(8), n.toFloat()))
            }
            bar.clipToOutline = true
            bar.background = a.round(C.GRAY_BG, 4f)
            box.add(bar)
            box.add(a.text("깨끗해요 ${v.clean} · 보통 ${v.normal} · 별로예요 ${v.bad}", 12f, C.TEXT3))
        }
        val tags = v.tags.entries.filter { it.value > 0 }
        if (tags.isNotEmpty()) {
            val tr = a.hbox(6)
            tags.forEach { tr.add(a.chip("${it.key} ${it.value}", C.AMBER_BG, C.AMBER_TEXT)) }
            box.add(tr)
        }
        var mineV = v.mine ?: -1
        val chosen = myTags.toMutableSet()
        fun send() {
            if (mineV < 0) { a.toast("먼저 깨끗해요/보통/별로예요 중 하나를 눌러 주세요"); return }
            Login.need(a, "평가하려면") {
                a.lifecycleScope.launch {
                    try { Cloud.vote(p.id, mineV, chosen.toList()); a.toast("평가를 저장했어요") } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }
        }
        box.add(a.text("내 평가", 12f, C.TEXT2, 600))
        // 고르기만 하면 저장 안 됨 → '평가 저장'을 눌러야 서버에 올라감
        box.add(a.segmented(listOf("깨끗해요", "보통", "별로예요"), if (mineV < 0) -1 else mineV) { mineV = it })
        // 휴지: 있었어요 / 없었어요 (없었어요 = '휴지 없음'으로 저장)
        val paperRow = a.hbox(10)
        paperRow.add(a.text("휴지", 15f, C.TEXT, 600))
        paperRow.add(a.segmented(listOf("있었어요", "없었어요"), if (chosen.contains("휴지 없음")) 1 else if (v.mine != null) 0 else -1) {
            if (it == 1) chosen.add("휴지 없음") else chosen.remove("휴지 없음")
        }, 1f)
        box.add(paperRow)
        box.add(a.toggleRow("고장 난 곳이 있었어요", chosen.contains("고장")) { on ->
            if (on) chosen.add("고장") else chosen.remove("고장")
        })
        box.add(a.btn("평가 저장", C.BLUE, Color.WHITE) { send() })
    }

    private fun fillMemos(a: MainActivity, box: LinearLayout, p: PublicToilet, list: List<MemoItem>) {
        box.removeAllViews()
        val blocked = a.prefs.blocked
        val shown = list.filter { it.uid !in blocked }
        box.add(a.text("사용자 메모 ${shown.size}", 15f, C.TEXT, 700))
        for (m in shown) {
            val card = a.vbox(4).apply {
                background = a.round(0xFFFFFFFF.toInt(), 14f)
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            }
            val meta = a.hbox(6)
            val isMine = m.uid == Cloud.uid
            if (isMine) meta.add(a.chip("내 메모", C.BLUE_BG, C.BLUE_TEXT))
            meta.add(a.text("${m.nick} · ${Geo.ago(m.at)}", 12f, C.TEXT3), 1f)
            fun link(s: String, col: Int, on: () -> Unit) = a.text(s, 13f, col, 600).apply {
                setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4)); setOnClickListener { on() }
            }
            if (isMine) {
                meta.add(link("수정", C.TEXT2) {
                    a.ask("메모 수정", "메모", "", m.text) { nt ->
                        a.lifecycleScope.launch {
                            try { Cloud.editMemo(m.id, nt); fillMemos(a, box, p, Cloud.memos(p.id)) } catch (e: Exception) { a.toast("실패: ${e.message}") }
                        }
                    }
                })
            }
            if (isMine || a.isAdmin) {
                meta.add(link("삭제", C.RED_TEXT) {
                    a.confirm("메모를 삭제할까요?", m.text, "삭제", true) {
                        a.lifecycleScope.launch {
                            try { Cloud.deleteMemo(m.id); fillMemos(a, box, p, Cloud.memos(p.id)) } catch (e: Exception) { a.toast("실패: ${e.message}") }
                        }
                    }
                })
            }
            if (!isMine) {
                meta.add(link("신고", C.TEXT2) {
                    Login.need(a, "신고하려면") {
                        a.lifecycleScope.launch {
                            try { Cloud.reportMemo(m); a.toast("신고했어요. 관리자가 확인해요") } catch (e: Exception) { a.toast("실패: ${e.message}") }
                        }
                    }
                })
                meta.add(link("차단", C.TEXT2) {
                    a.confirm("이 사용자를 차단할까요?", "차단하면 이 사람의 메모가 내 화면에서 안 보여요.", "차단") {
                        a.prefs.blocked = a.prefs.blocked + m.uid
                        fillMemos(a, box, p, list)
                    }
                })
            }
            card.add(meta)
            card.add(a.text(m.text, 15f, C.TEXT))
            box.add(card)
        }
        val (fb, et) = a.field("메모 남기기", "밤에는 입구 쪽 불이 꺼져 있어요")
        box.add(fb)
        val err = a.text("", 13f, C.RED_TEXT)
        box.add(err)
        box.add(a.primaryBtn("등록") {
            val t = et.text.toString().trim()
            if (t.isEmpty()) { err.text = "메모를 먼저 써 주세요"; return@primaryBtn }
            err.text = ""
            Login.need(a, "메모를 남기려면") {
                a.lifecycleScope.launch {
                    try { Cloud.addMemo(p.id, t); fillMemos(a, box, p, Cloud.memos(p.id)) } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }
        })
    }

    private fun reportUnavailable(a: MainActivity, p: PublicToilet) {
        val s = Sheet(a, "사용불가 알리기")
        s.add(a.text(p.name, 15f, C.TEXT2))
        s.add(a.text("사유를 골라 주세요. 24시간이 지나거나 누군가 '다시 열렸어요'를 누르면 자동으로 꺼져요.", 13f, C.TEXT3))
        val reasons = listOf("공사 중", "문 잠김", "고장", "청소 중", "기타")
        var pick = 0
        s.add(a.segmented(reasons.take(3), 0) { pick = it })
        s.add(a.segmented(reasons.drop(3), -1) { pick = it + 3 })
        s.add(a.btn("⛔ 사용불가로 알리기", C.RED, Color.WHITE) {
            s.dismiss()
            a.lifecycleScope.launch {
                try { Cloud.reportUnavailable(p.id, reasons[pick]); a.refreshUnavailable(); a.toast("알렸어요. 지도에 바로 표시돼요") } catch (e: Exception) { a.toast("실패: ${e.message}") }
            }
        })
        s.show()
    }

    // ---------------- 메뉴 ----------------

    fun menu(a: MainActivity) {
        val s = Sheet(a, "메뉴")
        // 계정
        val acc = a.vbox(8).apply {
            background = a.round(0xFFFFFFFF.toInt(), 16f)
            setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
        }
        if (Cloud.loggedIn) {
            acc.add(a.text("${Cloud.myName}", 16f, C.TEXT, 700))
            acc.add(a.text(if (a.isAdmin) "구글 계정 · 관리자" else "구글 계정으로 로그인됨", 12f, C.TEXT2))
        } else {
            acc.add(a.text("로그인하면 폰을 바꿔도 내 화장실을 불러올 수 있고, 그룹 공유와 메모를 쓸 수 있어요.", 13f, C.TEXT2))
            acc.add(a.primaryBtn(if (Login.ready(a)) "구글로 로그인" else "구글 로그인 (준비 중)") { s.dismiss(); Login.start(a) })
        }
        s.add(acc)

        s.add(a.btn("📁 나의 화장실") { s.dismiss(); myToilets(a) })
        s.add(a.btn("👥 그룹 관리") { Login.need(a, "그룹을 쓰려면") { s.dismiss(); groups(a) } })

        s.add(a.text("지도", 12f, C.TEXT2, 600))
        s.add(a.toggleRow("원 안 화장실만 보기", a.prefs.circleOnly, "끄면 원 밖 화장실도 보여요") {
            a.prefs.circleOnly = it
            a.setCircleShown(it)
            a.onCameraEnd()
        })
        s.add(a.toggleRow("공중화장실 보기", a.prefs.showPublic) {
            a.prefs.showPublic = it
            a.onCameraEnd()
        })
        s.add(a.text("내 위치 캐릭터", 12f, C.TEXT2, 600))
        s.add(a.segmented(listOf("동글이", "휴지 친구", "물방울이"), a.prefs.character) {
            a.prefs.character = it
            a.refreshCharacter()
        })

        s.add(a.btn("업데이트 확인 · 지금 v${BuildConfig.VERSION_NAME}") { a.checkUpdate(silent = false) })
        if (a.isAdmin || (Cloud.loggedIn && !a.realAdmin)) s.add(a.btn("🛠 관리자 페이지") { s.dismiss(); admin(a) })
        s.add(a.btn("개인정보처리방침") {
            a.showPrivacy()
        })
        if (Cloud.loggedIn) {
            val row = a.hbox(8)
            row.add(a.btn("로그아웃") { s.dismiss(); Login.logout(a) }, 1f)
            row.add(a.btn("계정 삭제", C.RED_BG, C.RED_TEXT) {
                s.dismiss()
                a.confirm("계정을 삭제할까요?", "내 화장실, 메모, 평가, 그룹(내가 만든 그룹)이 모두 지워지고 되돌릴 수 없어요.", "삭제", true) {
                    a.lifecycleScope.launch {
                        a.busy(true)
                        try {
                            Cloud.deleteAccountData()
                            try { Cloud.auth.currentUser?.delete()?.await() } catch (_: Exception) {}
                            Login.logout(a)
                            a.toast("계정을 삭제했어요")
                        } catch (e: Exception) {
                            a.toast("삭제하지 못했어요: ${e.message}")
                        } finally { a.busy(false) }
                    }
                }
            }, 1f)
            s.add(row)
        }
        s.show()
    }

    // ---------------- 그룹 ----------------

    fun groups(a: MainActivity) {
        val s = Sheet(a, "그룹")
        val listBox = a.vbox(8)
        s.add(listBox)
        fun reload() {
            a.lifecycleScope.launch {
                a.busy(true)
                try { a.groups = Cloud.myGroups() } catch (e: Exception) { a.toast("불러오지 못했어요: ${e.message}") }
                finally { a.busy(false) }
                listBox.removeAllViews()
                if (a.groups.isEmpty()) listBox.add(a.text("아직 그룹이 없어요. 새 그룹을 만들거나 초대 코드로 참여하세요.", 14f, C.TEXT2))
                val cfg = Cloud.groupConfig()
                for (g in a.groups) {
                    val card = a.vbox(6).apply {
                        background = a.round(0xFFFFFFFF.toInt(), 16f)
                        setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
                    }
                    val owner = g.isOwner(Cloud.uid)
                    val head = a.hbox(6)
                    head.add(a.text(g.name, 16f, C.TEXT, 700), 1f)
                    if (owner) head.add(a.chip("👑 방장", C.AMBER_BG, C.AMBER_TEXT))
                    else if (g.isSub(Cloud.uid)) head.add(a.chip("⭐ 부방장", C.PURPLE_BG, C.PURPLE_TEXT))
                    card.add(head)
                    card.add(a.text("${g.members.size} / ${g.limit(cfg.defaultMax)}명 · 초대 코드 ${g.code}", 13f, C.TEXT2))
                    if (g.canManage(Cloud.uid)) card.add(a.btn("👥 멤버·가입 요청 관리", C.PURPLE, Color.WHITE) { s.dismiss(); groupManage(a, g) })
                    val row = a.hbox(6)
                    row.add(a.btn("코드 복사") { a.copy(g.code) }, 1f)
                    if (owner) {
                        row.add(a.btn("이름 바꾸기") {
                            a.ask("그룹 이름 바꾸기", "그룹 이름", "우리 가족", g.name) { n ->
                                a.lifecycleScope.launch { try { Cloud.renameGroup(g.id, n); reload(); a.reloadMine() } catch (e: Exception) { a.toast("실패: ${e.message}") } }
                            }
                        }, 1f)
                        row.add(a.btn("삭제", C.RED_BG, C.RED_TEXT) {
                            a.confirm("그룹을 삭제할까요?", "그룹에 공유한 화장실도 모두 지워져요.", "삭제", true) {
                                a.lifecycleScope.launch { try { Cloud.deleteGroup(g.id); reload(); a.reloadMine() } catch (e: Exception) { a.toast("실패: ${e.message}") } }
                            }
                        }, 1f)
                    } else {
                        row.add(a.btn("나가기", C.RED_BG, C.RED_TEXT) {
                            a.confirm("그룹에서 나갈까요?", "${g.name} 그룹의 화장실이 더 이상 안 보여요.", "나가기", true) {
                                a.lifecycleScope.launch { try { Cloud.leaveGroup(g.id); reload(); a.reloadMine() } catch (e: Exception) { a.toast("실패: ${e.message}") } }
                            }
                        }, 1f)
                    }
                    card.add(row)
                    listBox.add(card)
                }
            }
        }
        val row = a.hbox(8)
        row.add(a.primaryBtn("새 그룹 만들기") {
            a.ask("새 그룹", "그룹 이름", "우리 가족") { n ->
                a.lifecycleScope.launch {
                    try { val g = Cloud.createGroup(n); a.toast("만들었어요. 초대 코드: ${g.code}"); reload(); a.reloadMine() } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }
        }, 1f)
        row.add(a.btn("코드로 참여") {
            a.ask("그룹 들어가기", "초대 코드 6자리", "ABC123") { c ->
                a.lifecycleScope.launch {
                    val msg = try { Cloud.requestJoin(c) } catch (e: Exception) { "실패: ${e.message}" }
                    a.confirm("그룹 들어가기", msg, "확인") {}
                }
            }
        }, 1f)
        s.add(row)
        s.show()
        reload()
    }

    // ---------------- 이용약관 ----------------

    fun terms(a: MainActivity) {
        val s = Sheet(a, "이용약관 동의")
        s.add(a.text(
            "• 다른 사람을 불쾌하게 하는 메모, 거짓 사용불가 신고, 광고는 올리지 않아요.\n" +
                "• 신고가 들어온 메모는 관리자가 지울 수 있어요.\n" +
                "• 공중화장실 메모·평가는 모든 사용자에게 보여요. 내 화장실은 나와 고른 그룹만 봐요.\n" +
                "• 위치는 앱을 쓰는 동안 지도 표시에만 쓰고 서버에 저장하지 않아요.",
            14f, C.TEXT2
        ))
        val row = a.hbox(8)
        row.add(a.btn("동의 안 함") { s.dismiss(); Login.logout(a) }, 1f)
        row.add(a.primaryBtn("동의해요") { a.prefs.termsAgreed = true; s.dismiss() }, 1f)
        s.add(row)
        s.dialog.setCancelable(false)
        s.show()
    }

    // ---------------- 관리자 ----------------

    fun admin(a: MainActivity) {
        val s = Sheet(a, "관리자 페이지")
        val box = a.vbox(10)
        s.add(box)
        s.show()
        a.lifecycleScope.launch {
            a.busy(true)
            try {
                val adminUid = Cloud.adminUid()
                if (adminUid == null) {
                    box.add(a.text("아직 관리자가 없어요. 처음 한 번만 등록하면 이 계정이 관리자가 돼요.", 14f, C.TEXT2))
                    box.add(a.primaryBtn("이 계정을 관리자로 등록") {
                        a.lifecycleScope.launch {
                            try { Cloud.claimAdmin(); a.realAdmin = true; a.refreshQuickBar(); s.dismiss(); admin(a) } catch (e: Exception) { a.toast("실패: ${e.message}") }
                        }
                    })
                    return@launch
                }
                if (adminUid != Cloud.uid) {
                    box.add(a.text("관리자만 볼 수 있어요.", 14f, C.TEXT2))
                    return@launch
                }
                a.realAdmin = true
                a.refreshQuickBar()
                fillAdmin(a, box)
            } catch (e: Exception) {
                box.add(a.text("불러오지 못했어요: ${e.message}", 14f, C.RED_TEXT))
            } finally {
                a.busy(false)
            }
        }
    }

    private suspend fun fillAdmin(a: MainActivity, box: LinearLayout) {
        // 제휴 가게: 열 때마다 서버에서 새로 불러옴
        a.reloadPartners()
        box.add(a.text("🤝 제휴 업체 목록 (${a.partners.size}곳)", 16f, C.TEXT, 800))
        if (a.partners.isEmpty()) box.add(a.text("아직 등록한 제휴 업체가 없어요.", 13f, C.TEXT3))
        box.add(a.btn("＋ 제휴 가게 등록", C.GREEN, Color.WHITE) {
            a.closeSheets()   // 창 닫고 지도에서 자리 정하기
            a.startPartnerPlacing(Partner())
        })
        for (p in a.partners.sortedBy { it.name }) {
            val r = a.hbox(8).apply {
                background = a.round(0xFFFFFFFF.toInt(), 12f)
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            }
            r.addView(photoView(a, p.bitmap(), 40))
            val tx = a.vbox(2)
            tx.add(a.text(p.name, 15f, C.TEXT, 700))
            tx.add(a.text(p.addr.ifBlank { "위치 정해짐" } + if (p.coupon) " · 🎁 쿠폰" else "", 12f, C.TEXT3))
            r.add(tx, 1f)
            r.add(a.text("📊 통계", 13f, C.GREEN_TEXT, 700).apply { setPadding(a.dp(8), a.dp(6), a.dp(4), a.dp(6)); setOnClickListener { partnerStats(a, p) } })
            r.setOnClickListener { a.closeSheets(); partnerDetail(a, p) }
            box.add(r)
        }

        // 가입자 목록 (총관리자)
        box.add(a.btn("👤 모든 가입자 보기", C.BLUE, Color.WHITE) { users(a) })

        // 그룹 설정 (총관리자)
        box.add(a.text("👥 그룹 설정", 16f, C.TEXT, 800))
        val gcfg = Cloud.groupConfig()
        val (gb1, defMax) = a.field("새 그룹 기본 최대 인원 (명)", "10", gcfg.defaultMax.toString())
        val (gb2, maxG) = a.field("1인당 최대 가입 그룹 (개)", "3", gcfg.maxGroups.toString())
        listOf(defMax, maxG).forEach { it.inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        box.add(gb1); box.add(gb2)
        box.add(a.btn("그룹 설정 저장", C.GREEN, Color.WHITE) {
            val d = defMax.text.toString().toIntOrNull()
            val m = maxG.text.toString().toIntOrNull()
            if (d == null || d < 2 || m == null || m < 1) { a.toast("숫자를 확인해 주세요 (인원 2명 이상, 그룹 1개 이상)"); return@btn }
            a.lifecycleScope.launch { try { Cloud.setGroupConfig(GroupConfig(d, m)); a.toast("저장했어요") } catch (e: Exception) { a.toast("실패: ${e.message}") } }
        })
        val allG = try { Cloud.allGroups() } catch (_: Exception) { emptyList() }
        box.add(a.text("그룹별 최대 인원 (${allG.size}개) · 누르면 바꾸기", 12f, C.TEXT2, 600))
        for (g in allG.sortedByDescending { it.members.size }) {
            val r = a.hbox(8).apply { background = a.round(0xFFFFFFFF.toInt(), 12f); setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10)) }
            r.add(a.text(g.name, 14f, C.TEXT, 700), 1f)
            val lim = a.text("${g.members.size} / ${g.limit(gcfg.defaultMax)}명", 14f, C.PURPLE_TEXT, 800)
            r.add(lim)
            r.setOnClickListener {
                a.ask("${g.name} 최대 인원", "최대 인원 (명)", "10", g.limit(gcfg.defaultMax).toString()) { v ->
                    val n = v.trim().toIntOrNull()
                    if (n == null || n < g.members.size || n < 2) { a.toast("지금 인원(${g.members.size}명)보다 크게 적어 주세요"); return@ask }
                    a.lifecycleScope.launch {
                        try { Cloud.setGroupMax(g.id, n); lim.text = "${g.members.size} / ${n}명"; a.toast("바꿨어요") } catch (e: Exception) { a.toast("실패: ${e.message}") }
                    }
                }
            }
            box.add(r)
        }

        box.add(a.text("사용 현황", 12f, C.TEXT2, 600))
        val grid = a.vbox(6)
        fun stat(label: String, v: String) {
            val r = a.hbox(8).apply {
                background = a.round(0xFFFFFFFF.toInt(), 12f)
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            }
            r.add(a.text(label, 14f, C.TEXT2), 1f)
            r.add(a.text(v, 17f, C.TEXT, 700))
            grid.add(r)
        }
        fun safe(block: suspend () -> Long): suspend () -> String = { try { block().toString() } catch (e: Exception) { "-" } }
        stat("전체 사용자", safe { Cloud.count("users") }())
        stat("최근 24시간 접속", safe { Cloud.activeToday() }())
        stat("그룹 수", safe { Cloud.count("groups") }())
        stat("사용자 메모", safe { Cloud.count("memos") }())
        val meta = try { Cloud.publicMeta() } catch (e: Exception) { emptyMap() }
        stat("공중화장실", (meta["count"] as? Number)?.toString() ?: "아직 없음")
        stat("마지막 공중화장실 업데이트", (meta["updatedAt"] as? Number)?.let { Geo.ago(it.toLong()) } ?: "-")
        box.add(grid)
        box.add(a.btn("서버 사용량 보기 (Firebase)") {
            a.open("https://console.firebase.google.com/project/my-toilet-ce481/usage")
        })

        // 공중화장실 업데이트
        box.add(a.text("공중화장실 데이터", 12f, C.TEXT2, 600))
        val (ub, urlField) = a.field("API 주소", PublicApi.DEFAULT_URL, (meta["apiUrl"] as? String) ?: PublicApi.DEFAULT_URL)
        box.add(ub)
        val out = a.text("", 13f, C.TEXT2).apply { setTextIsSelectable(true) }
        val apply = a.vbox(8)
        val row = a.hbox(8)
        row.add(a.btn("응답 미리보기") {
            a.lifecycleScope.launch {
                a.busy(true)
                try {
                    val raw = PublicApi.fetchRaw(urlField.text.toString().trim(), 1, 3)
                    val parsed = try {
                        val j: Any = if (raw.trim().startsWith("[")) org.json.JSONArray(raw) else org.json.JSONObject(raw)
                        PublicApi.findItems(j)?.optJSONObject(0)?.let { PublicApi.parse(it) }
                    } catch (e: Exception) { null }
                    out.text = (if (parsed != null) "✅ 읽기 성공: ${parsed.name} (${parsed.lat}, ${parsed.lon}) ${parsed.flags}\n\n" else "⚠ 항목을 못 읽었어요\n\n") + raw.take(1500)
                } catch (e: Exception) {
                    out.text = "실패: ${e.message}"
                } finally { a.busy(false) }
            }
        }, 1f)
        row.add(a.primaryBtn("새 데이터 확인") {
            apply.removeAllViews()
            a.lifecycleScope.launch {
                a.busy(true)
                val url = urlField.text.toString().trim()
                try {
                    out.text = "받는 중…"
                    val list = PublicApi.fetchAll(url) { n, total -> out.text = "받는 중… $n" + if (total > 0) " / $total" else "" }
                    if (list.isEmpty()) { out.text = "받은 화장실이 없어요. 응답 미리보기로 확인해 주세요"; return@launch }
                    out.text = "서버 데이터와 비교 중…"
                    val old = Cloud.allPublicCells()
                    val oldById = old.values.flatten().associateBy { it.id }
                    val newById = list.associateBy { it.id }
                    val added = newById.keys.count { it !in oldById }
                    val removed = oldById.keys.count { it !in newById }
                    val changed = newById.count { (id, p) -> oldById[id]?.let { it != p } ?: false }
                    val newCells = list.groupBy { Geo.cell(it.lat, it.lon) }
                    val changedCells = newCells.filter { (id, items) -> old[id]?.toSet() != items.toSet() }
                    val removedCells = old.keys - newCells.keys
                    out.text = "전체 ${list.size}곳\n새로 생긴 곳 ${added}곳 · 바뀐 곳 ${changed}곳 · 없어진 곳 ${removed}곳"
                    if (changedCells.isEmpty() && removedCells.isEmpty()) {
                        apply.add(a.text("업데이트할 게 없어요 ✅", 14f, C.GREEN_TEXT, 700))
                        Cloud.setPublicMeta(mapOf("count" to list.size, "updatedAt" to System.currentTimeMillis(), "apiUrl" to url))
                    } else {
                        apply.add(a.primaryBtn("서버에 반영하기 (${changedCells.size + removedCells.size}칸)") {
                            a.lifecycleScope.launch {
                                a.busy(true)
                                try {
                                    Cloud.writeCells(changedCells, removedCells)
                                    Cloud.setPublicMeta(mapOf("count" to list.size, "updatedAt" to System.currentTimeMillis(), "apiUrl" to url))
                                    apply.removeAllViews()
                                    apply.add(a.text("반영했어요 ✅ 사용자 지도에 바로 보여요", 14f, C.GREEN_TEXT, 700))
                                    a.onCameraEnd()
                                } catch (e: Exception) { a.toast("실패: ${e.message}") } finally { a.busy(false) }
                            }
                        })
                    }
                } catch (e: Exception) {
                    out.text = "실패: ${e.message}"
                } finally { a.busy(false) }
            }
        }, 1f)
        box.add(row)
        box.add(out)
        box.add(apply)

        // 신고된 메모
        box.add(a.text("신고된 메모", 12f, C.TEXT2, 600))
        val reports = try { Cloud.reports() } catch (e: Exception) { emptyList() }
        if (reports.isEmpty()) box.add(a.text("신고된 메모가 없어요", 14f, C.TEXT3))
        for (r in reports.take(50)) {
            val card = a.vbox(6).apply {
                background = a.round(0xFFFFFFFF.toInt(), 14f)
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            }
            card.add(a.text(r.text, 14f, C.TEXT))
            card.add(a.text("신고 ${Geo.ago(r.at)}", 12f, C.TEXT3))
            val rr = a.hbox(6)
            rr.add(a.btn("메모 삭제", C.RED_BG, C.RED_TEXT) {
                a.lifecycleScope.launch {
                    try { Cloud.deleteMemo(r.memoId); Cloud.dismissReport(r.id); card.visibility = View.GONE } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }, 1f)
            rr.add(a.btn("그대로 두기") {
                a.lifecycleScope.launch {
                    try { Cloud.dismissReport(r.id); card.visibility = View.GONE } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }, 1f)
            card.add(rr)
            box.add(card)
        }
    }
}
