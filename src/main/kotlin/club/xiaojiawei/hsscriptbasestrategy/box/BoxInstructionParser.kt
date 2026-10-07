package club.xiaojiawei.hsscriptbasestrategy.box

/**
 * 「推荐打法」面板指令解析（移植自 Python 版解析器，面板文本行格式固定）：
 * ```
 * 打法参考A
 * 操作4号位随从攻击
 * 小鸭子
 * 目标是对方1号位
 * 血法师萨尔诺斯
 * 打法参考B
 * 打出2号位随从
 * 赤红深渊
 * 放置于我方3号位
 * ```
 */
data class BoxTarget(
    /** enemy / friendly */
    val side: String,
    /** 1 基槽位；英雄目标为 null */
    val slot: Int?,
    val hero: Boolean,
    /** 面板给出的目标卡牌名（可能为 null） */
    var name: String?,
)

data class BoxInstruction(
    /** 打法参考A/B/C… */
    val tag: String,
    /** play | attack | hero_attack | hero_power | landmark */
    val kind: String,
    /** 主体槽位（1 基；hero_attack 为 null） */
    val slot: Int?,
    /** 主体卡牌名（可能为 null） */
    var name: String?,
    var target: BoxTarget?,
    /** 打出随从的落位（放置于我方N号位，1 基） */
    var placeSlot: Int?,
)

data class MulliganReplace(
    /** 1 基起手槽位 */
    val slot: Int,
    /** 卡牌名（可能为 null） */
    val name: String?,
)

object BoxInstructionParser {

    private val TAG_RE = Regex("^打法参考([A-Z])$")
    private val PLAY_RE = Regex("^打出\\s*(\\d{1,2})\\s*号位(.*)$")
    private val ATTACK_RE = Regex("^操作\\s*(\\d{1,2})\\s*号位(.+?)攻击$")
    private val ATTACK_HERO_RE = Regex("^操作我方英雄攻击$")
    private val LANDMARK_RE = Regex("^操作\\s*(\\d{1,2})\\s*号位(地标|星舰|泰坦)$")
    private val HERO_POWER_RE = Regex("^使用(?:\\s*\\d{1,2}\\s*号位)?英雄技能$")
    private val TARGET_RE = Regex("^目标是(对方|己方|我方)(?:(\\d{1,2})\\s*号位(随从|英雄)?|英雄)$")
    private val PLACE_RE = Regex("^(?:放置于|放置到|放在?)(我方|对手)\\s*(\\d{1,2})?\\s*号位$")
    private val NOISE_RE = Regex("该功能|盒子|Box君|仅供参考|网易|切换外置|对手记牌器|推荐打法")
    private val MULLIGAN_RE = Regex("^替换\\s*(\\d{1,2})\\s*号位卡牌$")

    /** 打出行尾缀的类型词，不是卡牌名 */
    private val TYPE_WORDS = setOf("随从", "法术", "武器", "英雄", "地标", "星舰", "泰坦", "装备")

    /** 解析推荐打法面板文本为结构化指令序列（按 A/B/C… 顺序） */
    fun parseInstructions(text: String?): List<BoxInstruction> {
        if (text.isNullOrBlank()) return emptyList()
        val lines = text.lines().map { it.trim() }

        // 按打法参考A/B/C…分块
        val blocks = mutableListOf<Pair<String, MutableList<String>>>()
        var curTag: String? = null
        var curLines: MutableList<String>? = null
        for (ln in lines) {
            if (ln.isEmpty() || NOISE_RE.containsMatchIn(ln)) continue
            val tagMatch = TAG_RE.matchEntire(ln)
            if (tagMatch != null) {
                if (curTag != null && curLines != null) blocks.add(curTag to curLines)
                curTag = tagMatch.groupValues[1]
                curLines = mutableListOf()
                continue
            }
            curLines?.add(ln)
        }
        if (curTag != null && curLines != null) blocks.add(curTag to curLines)

        val out = mutableListOf<BoxInstruction>()
        for ((tag, blockLines) in blocks) {
            var instr: BoxInstruction? = null
            for (ln in blockLines) {
                val play = PLAY_RE.matchEntire(ln)
                if (play != null) {
                    val rest = play.groupValues[2].trim()
                    instr = BoxInstruction(
                        tag = tag,
                        kind = "play",
                        slot = play.groupValues[1].toInt(),
                        name = rest.takeIf { it.isNotEmpty() && it !in TYPE_WORDS },
                        target = null,
                        placeSlot = null,
                    )
                    out.add(instr)
                    continue
                }
                if (ATTACK_HERO_RE.matchEntire(ln) != null) {
                    instr = BoxInstruction(tag, "hero_attack", null, null, null, null)
                    out.add(instr)
                    continue
                }
                val attack = ATTACK_RE.matchEntire(ln)
                if (attack != null) {
                    val what = attack.groupValues[2].trim()
                    instr = BoxInstruction(
                        tag = tag,
                        kind = if (what == "地标" || what == "星舰" || what == "泰坦") "landmark" else "attack",
                        slot = attack.groupValues[1].toInt(),
                        name = null,
                        target = null,
                        placeSlot = null,
                    )
                    out.add(instr)
                    continue
                }
                val landmark = LANDMARK_RE.matchEntire(ln)
                if (landmark != null) {
                    instr = BoxInstruction(tag, "landmark", landmark.groupValues[1].toInt(), null, null, null)
                    out.add(instr)
                    continue
                }
                if (HERO_POWER_RE.matchEntire(ln) != null) {
                    instr = BoxInstruction(tag, "hero_power", null, null, null, null)
                    out.add(instr)
                    continue
                }
                val target = TARGET_RE.matchEntire(ln)
                if (target != null) {
                    val cur = instr ?: continue
                    val side = if (target.groupValues[1] == "对方") "enemy" else "friendly"
                    val slotStr = target.groupValues[2]
                    cur.target = BoxTarget(
                        side = side,
                        slot = slotStr.toIntOrNull(),
                        hero = slotStr.isEmpty() || target.groupValues[3] == "英雄",
                        name = null,
                    )
                    continue
                }
                val place = PLACE_RE.matchEntire(ln)
                if (place != null) {
                    val cur = instr
                    if (cur?.kind == "play") cur.placeSlot = place.groupValues[2].toIntOrNull()
                    continue
                }
                // 其余非空行：先填目标名，再填主体名
                val cur = instr ?: continue
                val t = cur.target
                if (t != null && t.name == null) {
                    t.name = ln
                } else if (cur.name == null) {
                    cur.name = ln
                }
            }
        }
        return out
    }

    /**
     * 解析起手阶段换牌指令（替换N号位卡牌 + 随后一行的卡牌名）
     */
    fun parseMulliganReplaces(text: String?): List<MulliganReplace> {
        if (text.isNullOrBlank()) return emptyList()
        val lines = text.lines().map { it.trim() }
        val out = mutableListOf<MulliganReplace>()
        for (i in lines.indices) {
            val m = MULLIGAN_RE.matchEntire(lines[i]) ?: continue
            val slot = m.groupValues[1].toIntOrNull() ?: continue
            if (slot !in 1..5) continue
            var name: String? = null
            for (j in i + 1 until minOf(i + 5, lines.size)) {
                val nxt = lines[j]
                if (nxt.isEmpty()) continue
                if (MULLIGAN_RE.matchEntire(nxt) != null || TAG_RE.matchEntire(nxt) != null) break
                if (NOISE_RE.containsMatchIn(nxt)) break
                name = nxt
                break
            }
            out.add(MulliganReplace(slot, name))
        }
        return out
    }
}
