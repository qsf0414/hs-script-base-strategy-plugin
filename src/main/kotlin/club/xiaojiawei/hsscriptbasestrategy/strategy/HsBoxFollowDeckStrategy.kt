package club.xiaojiawei.hsscriptbasestrategy

import club.xiaojiawei.hsscriptbasestrategy.box.BoxConfig
import club.xiaojiawei.hsscriptbasestrategy.box.BoxInstruction
import club.xiaojiawei.hsscriptbasestrategy.box.BoxInstructionParser
import club.xiaojiawei.hsscriptbasestrategy.box.BoxManager
import club.xiaojiawei.hsscriptbasestrategy.box.OcrServiceManager
import club.xiaojiawei.hsscriptbasestrategy.box.GameScreenOcr
import club.xiaojiawei.hsscriptbasestrategy.box.BoxTarget
import club.xiaojiawei.hsscriptbasestrategy.box.DevToolsClient
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.isInValid
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptcardsdk.util.CardDBUtil
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil
import club.xiaojiawei.hsscriptbasestrategy.util.DeckStrategyUtil
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import club.xiaojiawei.hsscriptstrategysdk.TimelineEvent

/**
 * 「盒子跟随」策略：跟随网易炉石盒子「打法参考」面板的对局决策。
 *
 * 原理：以 --remote-debugging-port 启动盒子（未带参自动重启），通过
 * CEF DevTools 协议毫秒级直读「推荐打法」面板实时 DOM，解析
 * 打法参考A/B/C 指令序列并映射为脚本动作：
 *   打出N号位随从/武器（+放置于我方N号位 / 目标行）
 *   操作N号位随从攻击 / 操作我方英雄攻击（+目标是对方/己方N号位或英雄）
 *   操作N号位地标 / 使用英雄技能
 *   替换N号位卡牌（起手换牌）
 * 每条指令先做卡牌名前置校验（面板未刷新的过期指令直接跳过），
 * 执行后重读面板自同步；盒子未开/无指令时回落基础策略逻辑。
 *
 * 回溯（时间线）机制：SDK 原生回调 [execChooseTimeLine]，选维持——
 * 不回滚局面，状态保持可预测。
 */
class HsBoxFollowDeckStrategy : DeckStrategy() {

    private val config = BoxConfig.INSTANCE
    private val devTools = DevToolsClient(config.devToolsPort)

    /** 本回合内打不出而跳过的手牌 entityId */
    private val skippedHandCardIds = mutableSetOf<String>()

    override fun name(): String = "盒子跟随"

    override fun description(): String =
        "跟随网易炉石盒子「打法参考」出牌/攻击/换牌（DevTools 直读），无指令时回落基础策略逻辑"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.CASUAL, RunModeEnum.PRACTICE)

    override fun deckCode(): String = ""

    override fun id(): String = "b0x-f011-4ce-8a9d-boxfollow0001"

    override fun referCardInfo(): Boolean = true

    override fun reset() {
        super.reset()
        skippedHandCardIds.clear()
        stopChoiceMonitor()
    }

    // ================= 发现选牌 =================

    /**
     * 发现选牌跟随盒子：把候选卡的中文名逐个到「推荐打法」面板文本里
     * 匹配——盒子推荐哪张，哪张的名字就会出现在面板上（不依赖盒子的
     * 发现指令具体格式）。无命中时选第一张。
     */
    override fun executeDiscoverChooseCard(vararg cards: Card): Int {
        if (!config.followPlay) return 0
        val text = readPanel()
        if (text.isNullOrBlank()) return 0
        cards.forEachIndexed { idx, card ->
            val name = cardDisplayName(card)
            if (!name.isNullOrBlank() && name != card.cardId && text.contains(name)) {
                log.info { "[盒子|发现] 选牌命中面板推荐「$name」（第${idx + 1}张）" }
                return idx
            }
        }
        log.info { "[盒子|发现] 选牌未命中面板推荐 → 选第一张" }
        return 0
    }

    // ================= 时间线（回溯/维持） =================

    /** 回溯机制官方钩子：一律维持当前时间线（不回滚局面，状态保持可预测） */
    override fun execChooseTimeLine(timeLineEvent: TimelineEvent) {
        if (!timeLineEvent.isKeepTime()) {
            log.info { "[盒子|时间线] 维持事件线（不回溯）" }
            timeLineEvent.keep()
        }
    }

    // ================= 发现/抉择监控线程 =================

    /**
     * 宿主对「我方回合 + 2 张候选」的发现不路由给策略插件（官方门槛
     * 只覆盖 3+ 张我方回合 / 2 张非我方回合），此类发现会卡住回合。
     * 插件侧自救：本线程轮询盒子面板，出现「选择N号位卡牌」指令时
     * 直接调用宿主几何 API 点选对应位置，点后复核面板直至抉择消失。
     */
    @Volatile private var choiceMonitorStop = false
    private var choiceMonitor: Thread? = null

    private fun startChoiceMonitor() {
        if (choiceMonitor?.isAlive == true) return // 跨回合常驻，正在监控就不动
        if (!config.followPlay) return
        choiceMonitorStop = false
        choiceMonitor = Thread {
            val deadline = System.currentTimeMillis() + 120_000
            try {
                while (!choiceMonitorStop && System.currentTimeMillis() < deadline) {
                    if (WAR.isInValid()) return@Thread
                    val text = readPanel()
                    if (text != null) {
                        val m = CHOOSE_SLOT_RE.find(text)
                        if (m != null && WAR.currentPlayer === WAR.me) {
                            // 让宿主先接手（3+ 候选的发现宿主会路由并置
                            // isChooseCardTime）；2 秒后仍无人认领才是
                            // 宿主缺口（我方回合+2 候选），监控才代点，
                            // 避免与宿主双重点击乱选
                            Thread.sleep(2000)
                            if (isHostHandlingChoice()) {
                                log.debug { "[盒子|发现] 宿主已接手该发现，让路" }
                                return@Thread
                            }
                            val slot = m.groupValues[2].toIntOrNull() ?: 1
                            val name = m.groupValues[3].takeIf { it.isNotBlank() }
                                ?: nextLineAfter(text, m.range.last)
                            log.info { "[盒子|发现] 抉择：第${slot}号位 ${name ?: ""}" }
                            // 优先 OCR：在游戏画面里找推荐卡名的真实像素位置精确点击
                            var handled = false
                            if (!name.isNullOrBlank() &&
                                OcrServiceManager.ensureReady(
                                    config.ocrApiUrl, config.ocrPythonExe, config.ocrServiceDir
                                )
                            ) {
                                val pos = GameScreenOcr.locateTextOnScreen(config.ocrApiUrl, name)
                                if (pos != null) {
                                    log.debug { "[盒子|OCR] 定位「$name」@(${pos.first},${pos.second})" }
                                    GameScreenOcr.clickAt(pos.first, pos.second)
                                    Thread.sleep(1500)
                                    val after = readPanel()
                                    handled = after == null || CHOOSE_SLOT_RE.find(after) == null
                                    if (handled) {
                                        log.info { "[盒子|发现] 抉择已处理（OCR 精确点击）" }
                                        return@Thread
                                    }
                                } else {
                                    log.debug { "[盒子|OCR] 未定位到「$name」→ 回落几何点击" }
                                }
                            }
                            // 几何回落：按 N 号位在 3 选 1 布局点选，邻位试探
                            val positions = buildList {
                                add(slot - 1)
                                if (slot - 1 in 1..2) add(slot) else if (slot - 1 == 0) add(1) else add(slot - 2)
                            }.distinct()
                            for (pos in positions) {
                                clickDiscoverPosition(pos, 3)
                                Thread.sleep(1500)
                                val after = readPanel()
                                if (after == null || CHOOSE_SLOT_RE.find(after) == null) {
                                    log.info { "[盒子|发现] 抉择已处理（点了第${pos + 1}个位置）" }
                                    return@Thread
                                }
                            }
                            log.warn { "[盒子|发现] 抉择多次点选未消除 → 交由回合超时兜底" }
                            return@Thread
                        }
                    }
                    Thread.sleep(600)
                }
            } catch (e: InterruptedException) {
                log.debug { "[盒子|发现] 监控线程被中断停止" }
            } catch (e: Exception) {
                log.warn { "[盒子|发现] 监控线程异常: ${e.message}" }
            }
        }
        choiceMonitor?.isDaemon = true
        choiceMonitor?.start()
    }

    private fun stopChoiceMonitor() {
        choiceMonitorStop = true
        choiceMonitor?.interrupt()
        choiceMonitor = null
    }

    private fun nextLineAfter(text: String, rangeLast: Int): String? =
        text.substring((rangeLast + 1).coerceAtMost(text.length))
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { !it.startsWith("该功能") && !it.startsWith("打法参考") }

    /**
     * 反射调用宿主 GameUtil.chooseDiscoverCard(index, size) 点选发现位。
     * 宿主类对插件父类加载器可见（运行时可用），但不参与编译期依赖，
     * 保持插件与官方版本解耦。
     */
    private fun clickDiscoverPosition(index: Int, size: Int): Boolean = runCatching {
        val cls = Class.forName("club.xiaojiawei.hsscript.utils.GameUtil")
        val instance = cls.getDeclaredField("INSTANCE").get(null)
        cls.methods.first { it.name == "chooseDiscoverCard" }
            .invoke(instance, index, size)
        true
    }.onFailure { log.warn { "[盒子|发现] 发现位点击反射失败: ${it.message}" } }
        .getOrDefault(false)

    /** 反射读取宿主 WarEx.war.isChooseCardTime：宿主正在处理该发现时返回 true */
    private fun isHostHandlingChoice(): Boolean = runCatching {
        val warExClass = Class.forName("club.xiaojiawei.hsscript.bean.single.WarEx")
        val warEx = warExClass.getDeclaredField("INSTANCE").get(null)
        val war = warExClass.methods.firstOrNull { it.name == "getWar" }?.invoke(warEx)
            ?: return false
        war.javaClass.methods.firstOrNull { it.name == "isChooseCardTime" }
            ?.invoke(war) as? Boolean ?: false
    }.getOrDefault(false)

    // ================= 起手换牌 =================

    override fun executeChangeCard(cards: HashSet<Card>) {
        if (!config.followMulligan) {
            fallbackChangeCard(cards)
            return
        }
        val panelText = readPanel()
        if (panelText == null) {
            // 盒子不可读才按费用回落
            fallbackChangeCard(cards)
            return
        }
        val replaces = BoxInstructionParser.parseMulliganReplaces(panelText)
        if (replaces.isEmpty()) {
            // 盒子明确推荐保留全部：尊重之，一张不换
            log.info { "[盒子|起手] 面板可读 → 保留全部卡牌" }
            return
        }
        val hand = WAR.me.handArea.cards.toList()
        for ((slot, name) in replaces) {
            val card = hand.getOrNull(slot - 1) ?: continue
            if (!nameMatches(card, name)) continue // 面板指令与手牌不符：过期，跳过
            log.info { "[盒子|起手] 换掉第${slot}张 ${cardDisplayName(card)}" }
            cards.remove(card)
        }
    }

    private fun fallbackChangeCard(cards: HashSet<Card>) {
        // 与基础策略一致：换掉高费牌
        cards.toList().forEach { if (it.cost > 2) cards.remove(it) }
    }

    // ================= 出牌主逻辑 =================

    override fun executeOutCard() {
        val me = WAR.me
        val rival = WAR.rival
        if (!me.isValid() || !rival.isValid()) return
        skippedHandCardIds.clear()

        var executed = 0
        var stagnant = 0
        var skipped = 0
        var fellBack = false
        val attempts = mutableMapOf<String, Int>()
        if (config.followPlay) {
            startChoiceMonitor()
            while (executed < MAX_ACTIONS_PER_TURN) {
                val instructions = readInstructions() ?: break // DevTools 不可用 → 回落
                if (instructions.isNotEmpty()) {
                    log.info { "[盒子|指令] 回合${me.turn} 面板${instructions.size}条: " +
                        instructions.joinToString("/") { "${it.tag}:${it.kind}${it.slot ?: ""}" } }
                }
                val pick = pickInstruction(instructions, me, rival, attempts)
                if (pick == null) {
                    if (instructions.isNotEmpty()) {
                        log.info { "[盒子|回落] 面板指令均不可执行（费用/名称/状态不符）→ 内置逻辑接管" }
                        fellBack = true
                    }
                    break
                }
                val (ins, card, desc, run) = pick
                val before = snapshot(me, rival)
                log.info { "[盒子|执行] ${ins.tag}:${ins.kind}${ins.slot ?: ""} $desc …" }
                runInstruction(ins, run)
                executed++
                Thread.sleep(config.actionExtraDelayMs) // 等内存状态同步（动作自带停顿）
                val progressed = snapshot(me, rival) != before
                stagnant = if (progressed) 0 else stagnant + 1
                if (!progressed && ins.kind == "play" && card != null) {
                    val n = (attempts[card.entityId] ?: 0) + 1
                    attempts[card.entityId] = n
                    if (n >= 2) {
                        // 两次都未生效：标记跳过，避免连环误点
                        skippedHandCardIds.add(card.entityId)
                        skipped++
                        log.info { "[盒子|执行] ${pick.handCard?.let { cardDisplayName(it) } ?: "手牌"} 连续未生效×2 → 本回合跳过" }
                    }
                }
                if (stagnant >= 3) {
                    log.info { "[盒子|回落] 连续未生效×3 → 内置逻辑接管" }
                    fellBack = true
                    break
                }
            }
        }
        if (executed == 0 && config.fallbackBase) {
            fallbackBase(me, rival)
            fellBack = true
        }
        log.info {
            "[盒子|摘要] 回合${me.turn}: 执行${executed} 跳过${skipped} " +
                "回落=${if (fellBack) "是" else "否"}"
        }
        // 关键：发现/抉择面板会在此刻打开（战吼/英雄技能触发），而宿主在
        // 本方法返回后立即启动收尾盲点循环（右键取消+狂点结束回合+点
        // 发现位0），会与选择线程赛跑抢答。扣住不返回，直到抉择解决。
        val waitDeadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < waitDeadline) {
            if (runCatching { WAR.isValid() }.getOrDefault(false) == false) break
            if (WAR.currentPlayer !== WAR.me) break // 回合已交出
            val pending = isHostHandlingChoice() ||
                (readPanel()?.let { CHOOSE_SLOT_RE.containsMatchIn(it) } ?: false)
            if (!pending) break
            Thread.sleep(800)
        }
        // 监控线程不在此处停止：面板可能后续再开，线程跨回合常驻
    }

    /** 盒子无指令/不可执行时的回落：直接复用同模块的基础策略逻辑 */
    private fun fallbackBase(me: Player, rival: Player) {
        runCatching {
            DeckStrategyUtil.powerCard(me, rival)
            DeckStrategyUtil.cleanPlay()
            DeckStrategyUtil.powerCard(me, rival)
            me.playArea.power?.let { power ->
                if (me.usableResource >= power.cost || power.cost == 0) {
                    power.action.power()
                }
            }
            DeckStrategyUtil.cleanPlay()
        }.onFailure { log.warn { "[盒子|回落] 基础策略逻辑异常: ${it.message}" } }
    }

    // ================= 指令 → 动作 =================

    private data class Pick(
        val ins: BoxInstruction,
        /** play 类指令的手牌实体（进展判定/跳过标记用） */
        val handCard: Card?,
        /** 本条尝试的手段描述（用于执行日志） */
        val desc: String,
        /** 动作执行体：true=执行成功 */
        val run: () -> Boolean,
    )

    private fun pickInstruction(
        instructions: List<BoxInstruction>,
        me: Player,
        rival: Player,
        attempts: Map<String, Int>,
    ): Pick? {
        for (ins in instructions) {
            when (ins.kind) {
                "play" -> {
                    val slot = ins.slot ?: continue
                    val card = findHandCard(me, slot, ins.name) ?: continue
                    if (card.entityId in skippedHandCardIds) continue
                    if (card.cost > me.usableResource) continue
                    val action = card.action
                    val n = attempts[card.entityId] ?: 0
                    // 目标确定（优先级：面板目标行 > 卡牌描述推断）
                    val targetCard: Card? = if (ins.target != null) {
                        val targetLine = ins.target ?: continue
                        val t = resolveTarget(targetLine, me, rival)
                        if (t == null) continue // 有目标行但解析失败：面板过期
                        t
                    } else {
                        inferTargetCard(card, me, rival)
                    }
                    val placeSlot = ins.placeSlot
                    val targetName = targetCard?.let { cardDisplayName(it) }
                    val attemptDescRun: Pair<String, () -> Boolean> = when {
                        // 指定目标：第 1 次宿主拖拽到目标；第 2 次抬起卡牌 +
                        // OCR 定位目标实际渲染位置精确点击（拖拽偶发不准时）
                        targetCard != null -> when (n) {
                            0 -> "拖拽到${targetName}" to { action.power(targetCard) != null }
                            1 -> {
                                val ocrRun = liftAndOcrClick(action, card, targetCard)
                                if (ocrRun != null) {
                                    "抬牌+OCR精确点击${targetName}" to ocrRun
                                } else {
                                    "再次拖拽到${targetName}" to { action.power(targetCard) != null }
                                }
                            }
                            else -> continue
                        }
                        // 明确需要目标但推断不出合法候选：打了也会被退回，跳过
                        cardNeedsTarget(card) -> continue
                        // 放置于我方N号位：power(index) 的 index 为 0 基插入位
                        placeSlot != null -> "放置到我方${placeSlot}号位" to { action.power(placeSlot - 1) != null }
                        card.cardType == CardTypeEnum.MINION -> "铺场拖到${me.playArea.cardSize() + 1}号位" to { action.power(me.playArea.cardSize()) != null }
                        // 任务牌等无目标法术：直接打出
                        else -> "直接打出" to { action.power() != null }
                    }
                    return Pick(ins, card, attemptDescRun.first, attemptDescRun.second)
                }
                "attack" -> {
                    val slot = ins.slot ?: continue
                    val attacker = findMyPlayCard(me, slot, ins.name) ?: continue
                    if (!attacker.canAttack() || attacker.atc <= 0) continue
                    val t = ins.target
                    if (t == null || (t.hero && t.side == "enemy")) {
                        return Pick(ins, attacker, "攻击敌方英雄") { attacker.action.attackHero() != null }
                    }
                    if (t.side == "friendly") continue // 攻击己方随从无法执行
                    val target = resolveTarget(t, me, rival) ?: continue
                    return Pick(ins, attacker, "攻击${cardDisplayName(target)}") { attacker.action.attack(target) != null }
                }
                "hero_attack" -> {
                    val hero = me.playArea.hero ?: continue
                    if (!hero.canAttack() || hero.atc <= 0) continue
                    val t = ins.target
                    if (t == null || (t.hero && t.side == "enemy")) {
                        return Pick(ins, hero, "英雄攻击敌方英雄") { hero.action.attackHero() != null }
                    }
                    val target = resolveTarget(t, me, rival) ?: continue
                    return Pick(ins, hero, "英雄攻击${cardDisplayName(target)}") { hero.action.attack(target) != null }
                }
                "landmark" -> {
                    val slot = ins.slot ?: continue
                    val lm = findMyPlayCard(me, slot, ins.name) ?: continue
                    if (lm.cardType != CardTypeEnum.LOCATION || lm.isLocationActionCooldown) continue
                    val t = ins.target
                    if (t == null) {
                        return Pick(ins, lm, "激活地标") { lm.action.lClick() != null }
                    }
                    val target = resolveTarget(t, me, rival, allowFriendly = true) ?: continue
                    return Pick(ins, lm, "激活地标+指向${cardDisplayName(target)}") {
                        val first = lm.action.lClick()
                        if (first == null) false else lm.action.pointTo(target) != null
                    }
                }
                "hero_power" -> {
                    val power = me.playArea.power ?: continue
                    if (!power.canPower() || power.cost > me.usableResource) continue
                    val t = ins.target
                    if (t == null || (t.hero && t.side == "enemy")) {
                        return Pick(ins, power, "使用英雄技能") { power.action.power() != null }
                    }
                    val target = resolveTarget(t, me, rival) ?: continue
                    return Pick(ins, power, "英雄技能+指向${cardDisplayName(target)}") {
                        val first = power.action.power()
                        if (first == null) false else power.action.pointTo(target) != null
                    }
                }
            }
        }
        return null
    }

    private fun cardText(card: Card): String? = runCatching {
        CardUtil.getCardText(card.cardId)
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace(Regex("\\$\\d+"), "")
    }.getOrNull()

    private val TARGET_TEXT_KEYWORDS = listOf(
        "一个随从", "一名随从", "敌方英雄", "友方随从", "你的随从",
        "一个友方", "敌方角色", "一个角色",
    )

    /** 卡牌描述表明需要指定目标（"随机"类除外） */
    private fun cardNeedsTarget(card: Card): Boolean {
        val text = cardText(card) ?: return false
        if (text.contains("随机")) return false
        return TARGET_TEXT_KEYWORDS.any { text.contains(it) }
    }

    /**
     * 按卡牌描述推断目标实体（无面板目标行时）：
     *   友方随从类 → 我方第一个随从
     *   敌方随从/一个随从 → 血最低的敌方随从（伤害类默认敌方）
     *   敌方英雄 → 敌方英雄
     *   角色 → 血最低敌方随从，没有则敌方英雄
     */
    private fun inferTargetCard(card: Card, me: Player, rival: Player): Card? {
        val text = cardText(card) ?: return null
        val isFriendlyBuff = text.contains("获得") && !text.contains("消灭") &&
            !text.contains("造成") && !text.contains("伤害")
        return when {
            text.contains("友方随从") || text.contains("你的随从") ||
                text.contains("一个友方") ||
                (isFriendlyBuff && text.contains("随从")) ->
                me.playArea.cards.firstOrNull { it.isAlive() }
            text.contains("敌方随从") -> rival.playArea.cards
                .filter { it.isAlive() }.minByOrNull { it.blood() }
            text.contains("敌方英雄") -> rival.playArea.hero
            text.contains("一个随从") || text.contains("一名随从") ||
                text.contains("一个角色") || text.contains("敌方角色") ->
                rival.playArea.cards.filter { it.isAlive() }.minByOrNull { it.blood() }
                    ?: rival.playArea.hero
            else -> null
        }
    }

    /**
     * 「抬起手牌 + OCR 定位目标实际位置精确点击」：
     * 宿主几何拖拽偶发不准时的补救层。OCR 服务/数据集不可用或未命中
     * 返回 null（调用方回落下一层）。
     */
    private fun liftAndOcrClick(
        action: club.xiaojiawei.hsscriptcardsdk.CardAction,
        handCard: Card,
        targetCard: Card,
    ): (() -> Boolean)? {
        val targetName = cardDisplayName(targetCard)
        if (!OcrServiceManager.ensureReady(
                config.ocrApiUrl, config.ocrPythonExe, config.ocrServiceDir
            )
        ) {
            return null
        }
        val pos = GameScreenOcr.locateTextOnScreen(config.ocrApiUrl, targetName)
            ?: return null
        log.info { "[盒子] OCR 定位目标「$targetName」@(${pos.first},${pos.second})" }
        return {
            if (action.lClick() == null) {
                false
            } else {
                Thread.sleep(300)
                GameScreenOcr.clickAt(pos.first, pos.second)
                true
            }
        }
    }

    private fun runInstruction(ins: BoxInstruction, run: () -> Boolean) {
        runCatching { run() }
            .onFailure { log.warn { "[盒子|执行] 指令${ins.tag} 执行异常: ${it.message}" } }
            .onSuccess { ok -> if (!ok) log.debug { "[盒子|执行] 指令${ins.tag} 返回失败（鼠标动作未完成）" } }
    }

    // ================= 实体查找与校验 =================

    private fun cardDisplayName(card: Card): String =
        CardDBUtil.queryCardById(card.cardId).firstOrNull()?.name
            ?: card.entityName.takeUnless { it.startsWith("UNKNOWN") }
            ?: card.cardId

    /** 面板卡牌名与实体是否一致（面板无名视为匹配） */
    private fun nameMatches(card: Card, boxName: String?): Boolean {
        if (boxName.isNullOrBlank()) return true
        val n = cardDisplayName(card) ?: return true
        return n == boxName
    }

    /** 按面板名优先匹配手牌，无名时按下标 */
    private fun findHandCard(me: Player, slot: Int?, name: String?): Card? {
        val hand = me.handArea.cards.toList()
        if (hand.isEmpty()) return null
        if (!name.isNullOrBlank()) {
            return hand.firstOrNull { nameMatches(it, name) }
        }
        return slot?.let { hand.getOrNull(it - 1) }
    }

    private fun findMyPlayCard(me: Player, slot: Int?, name: String?): Card? {
        val playCards = me.playArea.cards.toList()
        if (playCards.isEmpty()) return null
        if (!name.isNullOrBlank()) {
            return playCards.firstOrNull { nameMatches(it, name) }
        }
        return slot?.let { playCards.getOrNull(it - 1) }
    }

    private fun resolveTarget(
        t: BoxTarget,
        me: Player,
        rival: Player,
        allowFriendly: Boolean = false,
    ): Card? {
        return if (t.side == "enemy") {
            if (t.hero || t.slot == null) {
                rival.playArea.hero
            } else {
                rival.playArea.cards.getOrNull(t.slot - 1)
                    ?.takeIf { nameMatches(it, t.name) }
            }
        } else {
            if (t.hero || t.slot == null) {
                me.playArea.hero
            } else {
                me.playArea.cards.getOrNull(t.slot - 1)
                    ?.takeIf { nameMatches(it, t.name) }
            }
        }
    }

    // ================= 面板读取 =================

    private fun readPanel(): String? {
        if (!BoxManager.ensureBoxReady(config)) return null
        return devTools.readPageText(PANEL_URL_KEY)
    }

    private fun readInstructions(): List<BoxInstruction>? {
        val text = readPanel() ?: return null
        return BoxInstructionParser.parseInstructions(text)
    }

    private data class Snapshot(
        val handSize: Int,
        val myBoardSize: Int,
        val rivalBoardSize: Int,
        val mana: Int,
    )

    private fun snapshot(me: Player, rival: Player): Snapshot = Snapshot(
        handSize = me.handArea.cardSize(),
        myBoardSize = me.playArea.cardSize(),
        rivalBoardSize = rival.playArea.cardSize(),
        mana = me.usableResource,
    )

    companion object {
        private const val MAX_ACTIONS_PER_TURN = 16

        /** 盒子「推荐打法」面板的页面 URL 特征 */
        private const val PANEL_URL_KEY = "client-jipaiqi/ladder-opp"

        /** 盒子发现/抉择指令：选择我方2号位卡牌 [卡牌名] */
        private val CHOOSE_SLOT_RE = Regex("选择(我方|对手)(\\d{1,2})\\s*号位卡牌(?:\\n\\s*([^\\n]+))?")

    }
}
