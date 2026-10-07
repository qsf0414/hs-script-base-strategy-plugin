package club.xiaojiawei.hsscriptbasestrategy

import club.xiaojiawei.hsscriptbasestrategy.box.BoxInstructionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 指令解析器离线用例（文本取自实机抓取的真实面板 DOM）
 */
class BoxInstructionParserTest {

    /** 实机抓取：攻击/英雄攻击/地标混合 */
    private val liveMixed = """
        网易炉石传说盒子
        切换外置

        对手记牌器
        推荐打法
        打法参考A

        操作4号位随从攻击

        小鸭子

        目标是对方1号位

        血法师萨尔诺斯

        打法参考B

        操作我方英雄攻击

        麦格尼·铜须

        目标是对方英雄

        巫妖王

        打法参考C

        操作1号位地标

        赤红深渊

        目标是己方2号位

        现场播报员

        该功能由网易炉石盒子提供
    """.trimIndent()

    /** 实机抓取：出牌+落位 */
    private val livePlay = """
        打法参考A

        打出2号位随从

        赤红深渊

        放置于我方3号位

        打法参考B

        使用英雄技能

        该功能由网易炉石盒子提供
    """.trimIndent()

    /** 实机抓取：起手换牌 */
    private val liveMulligan = """
        网易炉石传说盒子
        切换外置

        对手记牌器
        推荐打法
        打法参考A

        替换2号位卡牌

        赤红深渊

        替换3号位卡牌

        灼热裂隙

        该功能由网易炉石盒子提供
    """.trimIndent()

    @Test
    fun parseMixedInstructions() {
        val list = BoxInstructionParser.parseInstructions(liveMixed)
        assertEquals(3, list.size)

        val a = list[0]
        assertEquals("A", a.tag)
        assertEquals("attack", a.kind)
        assertEquals(4, a.slot)
        assertEquals("小鸭子", a.name)
        assertEquals("enemy", a.target?.side)
        assertEquals(1, a.target?.slot)
        assertEquals(false, a.target?.hero)
        assertEquals("血法师萨尔诺斯", a.target?.name)

        val b = list[1]
        assertEquals("hero_attack", b.kind)
        assertEquals("麦格尼·铜须", b.name)
        assertEquals(true, b.target?.hero)
        assertEquals("巫妖王", b.target?.name)

        val c = list[2]
        assertEquals("landmark", c.kind)
        assertEquals(1, c.slot)
        assertEquals("赤红深渊", c.name)
        assertEquals("friendly", c.target?.side)
        assertEquals(2, c.target?.slot)
        assertEquals("现场播报员", c.target?.name)
    }

    @Test
    fun parsePlayAndHeroPower() {
        val list = BoxInstructionParser.parseInstructions(livePlay)
        assertEquals(2, list.size)

        val a = list[0]
        assertEquals("play", a.kind)
        assertEquals(2, a.slot)
        assertEquals("赤红深渊", a.name)
        assertEquals(3, a.placeSlot)

        val b = list[1]
        assertEquals("hero_power", b.kind)
    }

    @Test
    fun parsePlayWithoutTypeWord() {
        val list = BoxInstructionParser.parseInstructions("打法参考A\n打出9号位随从\n龙巢守护者\n放置于我方5号位")
        assertEquals(1, list.size)
        assertEquals("龙巢守护者", list[0].name)
        assertEquals(5, list[0].placeSlot)
    }

    @Test
    fun parseMulliganReplaces() {
        val list = BoxInstructionParser.parseMulliganReplaces(liveMulligan)
        assertEquals(2, list.size)
        assertEquals(2, list[0].slot)
        assertEquals("赤红深渊", list[0].name)
        assertEquals(3, list[1].slot)
        assertEquals("灼热裂隙", list[1].name)
    }

    @Test
    fun parseEmptyAndNoise() {
        assertTrue(BoxInstructionParser.parseInstructions(null).isEmpty())
        assertTrue(BoxInstructionParser.parseInstructions("该功能由网易炉石盒子提供").isEmpty())
        assertTrue(BoxInstructionParser.parseMulliganReplaces("").isEmpty())
        // 攻击指令不应被误判为换牌
        assertTrue(BoxInstructionParser.parseMulliganReplaces("操作4号位随从攻击\n鸭妈妈").isEmpty())
    }
}
