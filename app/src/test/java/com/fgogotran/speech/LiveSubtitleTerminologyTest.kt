package com.fgogotran.speech

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveSubtitleTerminologyTest {
    @Test
    fun `all listed Chinese variants become Master when source contains master`() {
        val variants = listOf(
            "老闆", "老板", "店長", "店长", "主人", "師父", "师父", "師傅", "师傅",
            "大師", "大师", "達人", "达人", "名人", "主師", "主师", "主帥", "主帅"
        )
        for (locale in listOf("zh-Hans", "zh-Hant")) {
            for (sourceTitle in listOf("マスタ", "マスター", "ますた", "ますたー")) {
                for (term in variants) {
                    assertEquals("御主，请下达命令。", correct("$sourceTitle、命令を。", "$term，请下达命令。", locale))
                }
            }
        }
    }

    @Test
    fun `unrelated source and incomplete partial source stay unchanged`() {
        for (source in listOf("店長です。", "マス", "ます", "")) {
            assertEquals("老板、师父和达人。", correct(source, "老板、师父和达人。"))
        }
    }

    @Test
    fun `English and unsupported targets stay unchanged`() {
        for (locale in listOf("en", "ja-JP", "")) {
            for (sourceTitle in listOf("マスタ", "マスター", "ますた", "ますたー")) {
                assertEquals("Master, boss, teacher; 主人。", correct(sourceTitle, "Master, boss, teacher; 主人。", locale))
            }
        }
    }

    @Test
    fun `existing correct term empty text and no match stay unchanged`() {
        for (text in listOf("御主！", "", "请下达命令。")) {
            assertEquals(text, correct("マスター", text))
        }
    }

    @Test
    fun `multiple replacements preserve whitespace and punctuation`() {
        assertEquals("  御主……\n『御主』！  ", correct("マスター、マスター！", "  老闆……\n『主人』！  ", "zh-Hant"))
    }

    @Test
    fun `each partial or final correction is independent`() {
        assertEquals("御主！", correct("マスター", "主人！"))
        assertEquals("主人！", correct("", "主人！"))
    }

    @Test
    fun `clear verb forms are not counted as titles`() {
        val suffixes = listOf("する", "した", "して", "しよう", "します", "しました", "すれば", "でき", "出来", "され", "させ")
        for (locale in listOf("zh-Hans", "zh-Hant")) {
            for (sourceTitle in listOf("マスタ", "マスター", "ますた", "ますたー")) {
                for (suffix in suffixes) {
                    assertEquals("他是剑术达人。", correct("技術を$sourceTitle$suffix。", "他是剑术达人。", locale))
                }
            }
        }
    }

    @Test
    fun `embedded katakana words are not counted as titles`() {
        for (source in listOf("グランドマスター", "マスターピース", "マスターズ", "マスタード", "グランドマスタ", "マスタピース")) {
            assertEquals("主人。", correct(source, "主人。"))
        }
    }

    @Test
    fun `punctuation particles and honorifics preserve valid title matches`() {
        for (sourceTitle in listOf("マスタ", "マスター", "ますた", "ますたー")) {
            for (suffix in listOf("", "！", "？", "、命令を。", "は", "が", "を", "に", "の", "と", "へ", "も", "さん")) {
                assertEquals("御主！", correct("$sourceTitle$suffix", "主人！"))
            }
        }
    }

    @Test
    fun `Chinese compounds are protected while the actual title is corrected`() {
        for (locale in listOf("zh-Hans", "zh-Hant")) {
            val text = "主人，你是主人公。主人翁与名人堂。"
            assertEquals("御主，你是主人公。主人翁与名人堂。", correct("マスター、主人公は君だ。", text, locale))
            assertEquals("主人公、主人翁、名人堂。", correct("マスター", "主人公、主人翁、名人堂。", locale))
        }
    }

    @Test
    fun `too many translation candidates leave the entire subtitle unchanged`() {
        val text = "老板，店长在哪里？"
        assertEquals(text, correct("マスター、店長はどこ？", text))
    }

    @Test
    fun `too few translation candidates leave the entire subtitle unchanged`() {
        assertEquals("主人！", correct("マスター！マスター！", "主人！"))
    }

    @Test
    fun `already correct titles consume the matching budget`() {
        val text = "御主，他是名人。"
        assertEquals(text, correct("マスター、彼は名人だ。", text))
        assertEquals("御主！御主！", correct("マスター！マスター！", "御主！主人！"))
    }

    @Test
    fun `excluded source words do not consume the title budget`() {
        assertEquals("御主，请掌握这项技术。", correct("マスター、技術をマスターしてください。", "主人，请掌握这项技术。"))
        assertEquals("御主，杰作。", correct("マスター、マスターピース。", "主人，杰作。"))
    }

    @Test
    fun `correction is idempotent`() {
        val source = "マスター！マスター！"
        val corrected = correct(source, "主人！御主！")
        assertEquals("御主！御主！", corrected)
        assertEquals(corrected, correct(source, corrected))
    }

    @Test
    fun `each partial uses its own current source and candidate count`() {
        assertEquals("主人！", correct("マス", "主人！"))
        assertEquals("御主！", correct("マスタ", "主人！"))
        assertEquals("御主！", correct("マスター", "主人！"))
        assertEquals("主人、店长！", correct("マスター", "主人、店长！"))
        assertEquals("主人！", correct("", "主人！"))
        assertEquals("御主！御主！", correct("マスター、マスター", "主人！主人！"))
    }

    @Test
    fun `short spelling preserves compounds and the match count safeguard`() {
        for (locale in listOf("zh-Hans", "zh-Hant")) {
            assertEquals("御主，你是主人公。", correct("マスタ、主人公は君だ。", "主人，你是主人公。", locale))
            assertEquals("老板，店长在哪里？", correct("マスタ、店長はどこ？", "老板，店长在哪里？", locale))
            assertEquals("主人！", correct("マスタ！マスター！", "主人！", locale))
            assertEquals("御主！御主！", correct("マスタ！マスター！", "御主！主人！", locale))
        }
    }

    @Test
    fun `recorded concert subtitle title is corrected without changing the rest`() {
        val text = "大師就是首席小提琴手。我是表演者，對吧？"
        assertEquals(
            "御主就是首席小提琴手。我是表演者，對吧？",
            correct("マスター", text, "zh-Hant")
        )
    }

    @Test
    fun `hiragana birthday titles are corrected without changing the rest`() {
        for (sourceTitle in listOf("ますた", "ますたー")) {
            for (locale in listOf("zh-Hans", "zh-Hant")) {
                assertEquals(
                    "生日快樂，御主。",
                    correct("お誕生日おめでとう、$sourceTitle。", "生日快樂，師父。", locale)
                )
            }
        }
    }

    @Test
    fun `hiragana variants retain compound and match count safeguards`() {
        for (sourceTitle in listOf("ますた", "ますたー")) {
            for (locale in listOf("zh-Hans", "zh-Hant")) {
                assertEquals("師父！", correct("グランド$sourceTitle", "師父！", locale))
                assertEquals("師父！", correct("${sourceTitle}ピース", "師父！", locale))
                assertEquals("師父，店長在哪裡？", correct("$sourceTitle、店長はどこ？", "師父，店長在哪裡？", locale))
                assertEquals("師父！", correct("$sourceTitle！マスター！", "師父！", locale))
                assertEquals("御主！御主！", correct("$sourceTitle！マスター！", "御主！師父！", locale))
            }
        }
    }

    @Test
    fun `width spacing and mixed kana variants correct the birthday subtitle`() {
        val sourceTitles = listOf(
            "ﾏｽﾀｰ", "ﾏｽﾀ", "マｽﾀｰ", "ﾏスター",
            "マ スター", "マス ター", "マ ス タ", "マ ス タ ー",
            "ま すた", "ます たー", "ま す た ー", "まスたｰ",
            "マ　ス　タ　ー", "マ\tス\tタ\tー", "マ\u00A0ス\u202Fタ ー"
        )
        for (sourceTitle in sourceTitles) {
            for (locale in listOf("zh-Hans", "zh-Hant")) {
                assertEquals(
                    "生日快樂，御主。",
                    correct("お誕生日おめでとう、$sourceTitle。", "生日快樂，師父。", locale)
                )
            }
            assertEquals("Happy birthday, Master.", correct(sourceTitle, "Happy birthday, Master.", "en"))
        }
    }

    @Test
    fun `formatted compounds cannot fall back to a shortened title`() {
        val compounds = listOf(
            "ｸﾞﾗﾝﾄﾞﾏｽﾀｰ", "ﾏｽﾀｰﾋﾟｰｽ", "ﾏｽﾀｰﾄﾞ", "ﾏｽﾀｰｽﾞ",
            "グランドマ スター", "マ ス タ ーピース", "マ ス タ ード",
            "ま す た ーピース", "マ ス タピース"
        )
        for (source in compounds) {
            for (locale in listOf("zh-Hans", "zh-Hant")) {
                assertEquals("師父！", correct(source, "師父！", locale))
            }
        }
    }

    @Test
    fun `formatted source never joins words across rows or punctuation`() {
        for (source in listOf("マ\nスター", "マス\r\nター", "ま\nすた", "マ、スター", "マ。スター", "ﾏ ｽ")) {
            assertEquals("師父！", correct(source, "師父！", "zh-Hant"))
        }
    }

    @Test
    fun `formatted variants retain verb and count safeguards`() {
        val suffixes = listOf("する", "した", "して", "しよう", "します", "しました", "すれば", "でき", "出来", "され", "させ")
        for (sourceTitle in listOf("ﾏｽﾀｰ", "マ ス タ ー", "ま ス た", "ま す た ー")) {
            for (locale in listOf("zh-Hans", "zh-Hant")) {
                for (suffix in suffixes) {
                    assertEquals("他是劍術達人。", correct("技術を$sourceTitle$suffix。", "他是劍術達人。", locale))
                }
                assertEquals("師父，店長在哪裡？", correct("$sourceTitle、店長はどこ？", "師父，店長在哪裡？", locale))
                assertEquals("師父！", correct("$sourceTitle！ﾏｽﾀｰ！", "師父！", locale))
                assertEquals("御主！御主！", correct("$sourceTitle！ﾏｽﾀｰ！", "御主！師父！", locale))
                assertEquals("御主！", correct(sourceTitle, "御主！", locale))
            }
        }
    }

    @Test
    fun `Latin Master recognition corrects the Chinese birthday subtitle`() {
        for (sourceTitle in listOf("master", "Master", "MASTER", "mAsTeR", "Ｍａｓｔｅｒ", "ＭＡＳＴＥＲ")) {
            for (locale in listOf("zh-Hans", "zh-Hant")) {
                assertEquals(
                    "生日快樂，御主。",
                    correct("お誕生日おめでとう、$sourceTitle。", "生日快樂，師父。", locale)
                )
                assertEquals("御主！", correct("${sourceTitle}さん", "主人！", locale))
            }
        }
    }

    @Test
    fun `Latin Master does not match compounds identifiers or clear verb forms`() {
        val sources = listOf(
            "grandmaster", "GrandMaster", "masterpiece", "Masterpiece", "webmaster",
            "mastering", "mastered", "mastery", "masters", "master2", "master_name", "2master",
            "グランドMaster", "Masterピース", "技術をMasterする。", "技術をMasterして。"
        )
        for (source in sources) {
            for (locale in listOf("zh-Hans", "zh-Hant")) {
                assertEquals("師父！", correct(source, "師父！", locale))
            }
        }
    }

    @Test
    fun `Latin and Japanese titles share the existing matching budget`() {
        for (locale in listOf("zh-Hans", "zh-Hant")) {
            assertEquals("師父！", correct("Master、マスター！", "師父！", locale))
            assertEquals("御主！御主！", correct("Master、ﾏｽﾀｰ！", "御主！師父！", locale))
            assertEquals("御主，師父！", correct("Master！", "御主，師父！", locale))
            assertEquals("御主！", correct("Master、masterpiece。", "師父！", locale))
            assertEquals("御主！", correct("Master、技術をMasterしてください。", "師父！", locale))
        }
    }

    @Test
    fun `Latin recognition does not change English or unsupported subtitle targets`() {
        for (sourceTitle in listOf("master", "Master", "MASTER", "Ｍａｓｔｅｒ")) {
            for (locale in listOf("en", "ja-JP", "")) {
                assertEquals("Happy birthday, Master. 師父！", correct(sourceTitle, "Happy birthday, Master. 師父！", locale))
            }
        }
    }

    @Test
    fun `new variants retain source language and count safeguards`() {
        for (locale in listOf("zh-Hans", "zh-Hant")) {
            for (term in listOf("大師", "大师", "師傅", "师傅", "主師", "主师", "主帥", "主帅")) {
                for (source in listOf("", "マス", "演奏者です。", "技術をマスターする。")) {
                    assertEquals("$term！", correct(source, "$term！", locale))
                }
                assertEquals("$term！", correct("マスター、マスター！", "$term！", locale))
                assertEquals("御主，$term！", correct("マスター！", "御主，$term！", locale))
                assertEquals("御主，御主！", correct("マスタ、マスター！", "御主，$term！", locale))
                assertEquals("$term！", correct("マスター！", "$term！", "en"))
            }
        }
    }

    @Test
    fun `new title compounds and shop owner fragments remain unchanged`() {
        val compounds = listOf("大師兄", "大师兄", "大師姐", "大师姐", "店老闆", "店老板")
        for (locale in listOf("zh-Hans", "zh-Hant")) {
            for (compound in compounds) {
                assertEquals("$compound！", correct("マスター", "$compound！", locale))
                assertEquals("御主，$compound！", correct("マスタ", "大師，$compound！", locale))
                assertEquals("御主，$compound！", correct("マスター", "老板，$compound！", locale))
            }
        }
    }

    @Test
    fun `unapproved wider dictionary meanings remain unchanged`() {
        for (term in listOf("店主", "名家", "專家", "专家", "主宰", "主宰者", "老師", "老师", "先生", "船長", "船长", "碩士", "硕士", "掌握", "精通")) {
            for (locale in listOf("zh-Hans", "zh-Hant")) {
                assertEquals("$term！", correct("マスター", "$term！", locale))
            }
        }
    }

    @Test
    fun `all approved Servant translations become the target language term`() {
        val variants = listOf(
            "僕人", "仆人", "傭人", "佣人", "侍從", "侍从", "從者", "从者",
            "隨從", "随从", "僕從", "仆从", "僕役", "仆役", "侍者"
        )
        for (locale in listOf("zh-Hans", "zh-Hant")) {
            val expected = if (locale == "zh-Hant") "從者" else "从者"
            for (term in variants) {
                assertEquals("$expected，來了。", correct("サーヴァントが来ました。", "$term，來了。", locale))
            }
        }
    }

    @Test
    fun `Servant source supports widths horizontal spacing kana and Latin case`() {
        val sourceTerms = listOf(
            "サーヴァント", "ｻｰｳﾞｧﾝﾄ", "サーｳﾞｧﾝﾄ", "サーウ\u3099ァント",
            "サー ヴァント", "サ ー ヴ ァ ン ト", "サ　ー　ヴ　ァ　ン　ト", "サー\tヴァント",
            "さーゔぁんと", "さ ー ゔ ぁ ん と", "サーゔァんと",
            "サーバント", "さーばんと", "ｻｰﾊﾞﾝﾄ", "サーﾊﾞﾝﾄ", "サーハ\u3099ント",
            "サー バント", "サ ー バ ン ト", "さ ー ば ん と", "サーばンと",
            "サ　ー　バ　ン　ト", "サー\tバント", "サ\u00A0ー\u202Fバ ン ト",
            "servant", "Servant", "SERVANT", "sErVaNt", "Ｓｅｒｖａｎｔ", "ＳＥＲＶＡＮＴ",
            "servants", "Servants", "SERVANTS", "sErVaNtS", "Ｓｅｒｖａｎｔｓ", "ＳＥＲＶＡＮＴＳ"
        )
        for (source in sourceTerms) {
            assertEquals("從者！", correct(source, "僕人！", "zh-Hant"))
            assertEquals("从者！", correct(source, "仆人！", "zh-Hans"))
        }
    }

    @Test
    fun `Servant correction preserves FGO compound qualifiers`() {
        val sources = listOf(
            "グランドサーヴァント", "ｸﾞﾗﾝﾄﾞｻｰｳﾞｧﾝﾄ", "グランド サー ヴァント", "グランドServant",
            "グランドサーバント", "ｸﾞﾗﾝﾄﾞｻｰﾊﾞﾝﾄ", "グランド さーばんと", "グランドServants"
        )
        for (source in sources) {
            assertEquals("冠位從者，來了。", correct(source, "冠位僕人，來了。", "zh-Hant"))
            assertEquals("冠位从者，来了。", correct(source, "冠位仆人，来了。", "zh-Hans"))
        }
        assertEquals("從者宇宙。", correct("サーヴァント・ユニヴァース", "僕人宇宙。", "zh-Hant"))
        assertEquals("從者宇宙。", correct("サーバント・ユニヴァース", "隨從宇宙。", "zh-Hant"))
        assertEquals("從者宇宙。", correct("Servantユニヴァース", "僕人宇宙。", "zh-Hant"))
    }

    @Test
    fun `absent incomplete or unrelated Servant sources stay unchanged`() {
        val sources = listOf(
            "", "マスター", "こんにちは。", "サーヴァン", "サヴァント", "ｻｰｳﾞｧﾝ",
            "サーバン", "さーばん", "ｻｰﾊﾞﾝ", "サバント", "さばんと", "サー", "サー バ",
            "サー\nヴァント", "サー、ヴァント", "サー\r\nヴァント",
            "サー\nバント", "さー、ばんと", "サー\r\nバント", "サー バ\nント",
            "serv", "servanthood", "servantry", "unservant", "servant2", "servant_name",
            "unservants", "servants2", "servants_name", "2servants", "servantship", "ServantService"
        )
        for (source in sources) {
            for (locale in listOf("zh-Hans", "zh-Hant")) {
                assertEquals("僕人、仆人。", correct(source, "僕人、仆人。", locale))
                assertEquals("僕人！", correct(source, "僕人！", locale))
            }
        }
    }

    @Test
    fun `Master and Servant correct together without overlapping their dictionaries`() {
        val source = "Master、サーヴァントが来ました。"
        assertEquals("御主，從者來了。", correct(source, "師父，僕人來了。", "zh-Hant"))
        assertEquals("御主，从者来了。", correct(source, "师父，仆人来了。", "zh-Hans"))
        assertEquals("主人，從者來了。", correct("サーヴァント", "主人，僕人來了。", "zh-Hant"))
        assertEquals("御主，僕人來了。", correct("Master", "師父，僕人來了。", "zh-Hant"))
    }

    @Test
    fun `ambiguous Master does not block Servant and ambiguous Servant does not block Master`() {
        val source = "Master、サーヴァントが来ました。"
        assertEquals("師父和主人，從者來了。", correct(source, "師父和主人，僕人來了。", "zh-Hant"))
        assertEquals("御主，僕人和侍從來了。", correct(source, "師父，僕人和侍從來了。", "zh-Hant"))
        assertEquals("御主，僕人來了。", correct("Master、サーヴァント、サーヴァント。", "師父，僕人來了。", "zh-Hant"))
    }

    @Test
    fun `Servant budgets include correct terms and reject missing or surplus candidates`() {
        assertEquals("僕人和侍從。", correct("サーヴァント", "僕人和侍從。", "zh-Hant"))
        assertEquals("僕人！", correct("サーヴァント、Servant。", "僕人！", "zh-Hant"))
        assertEquals("從者，僕人！", correct("サーヴァント", "從者，僕人！", "zh-Hant"))
        assertEquals("從者，從者！", correct("サーヴァント、Servant。", "從者，僕人！", "zh-Hant"))
        assertEquals("從者，從者！", correct("サーヴァント、Servant。", "从者，僕人！", "zh-Hant"))
        assertEquals("从者，从者！", correct("サーヴァント、Servant。", "從者，仆人！", "zh-Hans"))
        assertEquals("來了。", correct("サーヴァント", "來了。", "zh-Hant"))
    }

    @Test
    fun `Servant correction is independent for partials finals and later utterances`() {
        assertEquals("僕人！", correct("サーヴァン", "僕人！", "zh-Hant"))
        assertEquals("從者！", correct("サーヴァント", "僕人！", "zh-Hant"))
        assertEquals("僕人！", correct("", "僕人！", "zh-Hant"))
        val source = "Master、サーヴァント。"
        val corrected = correct(source, "師父，僕人。", "zh-Hant")
        assertEquals("御主，從者。", corrected)
        assertEquals(corrected, correct(source, corrected, "zh-Hant"))
    }

    @Test
    fun `Servant correction preserves spacing punctuation and leaves wider meanings alone`() {
        assertEquals(
            "  從者……\n『從者』！  ",
            correct("サーヴァント、Servant。", "  仆人……\n『侍從』！  ", "zh-Hant")
        )
        for (term in listOf("主人", "師父", "奴隸", "奴隶", "服務員", "服务员", "使用者")) {
            assertEquals("$term！", correct("サーヴァント", "$term！", "zh-Hant"))
        }
    }

    @Test
    fun `Servant rules do not change English or unsupported target subtitles`() {
        for (source in listOf("サーヴァント", "ｻｰｳﾞｧﾝﾄ", "Servant", "Master、サーヴァント", "サーバント", "さーばんと", "ｻｰﾊﾞﾝﾄ", "Servants")) {
            for (locale in listOf("en", "ja-JP", "")) {
                assertEquals("Master, the Servant is here. 師父、僕人！", correct(source, "Master, the Servant is here. 師父、僕人！", locale))
            }
        }
    }

    @Test
    fun `new Servant aliases accept particles honorifics and punctuation`() {
        for (source in listOf("サーバント", "さーばんと", "ｻｰﾊﾞﾝﾄ", "サー バント", "Servants")) {
            for (suffix in listOf("", "！", "？", "、来ました。", "は", "が", "を", "に", "の", "と", "へ", "も", "さん")) {
                assertEquals("從者！", correct("$source$suffix", "侍者！", "zh-Hant"))
                assertEquals("从者！", correct("$source$suffix", "仆役！", "zh-Hans"))
            }
        }
    }

    @Test
    fun `new Servant translations require source evidence and exact matching counts`() {
        val terms = listOf("隨從", "随从", "僕從", "仆从", "僕役", "仆役", "侍者")
        for (locale in listOf("zh-Hans", "zh-Hant")) {
            val expected = if (locale == "zh-Hant") "從者" else "从者"
            for (term in terms) {
                for (source in listOf("", "Master", "サーバン", "サー", "Serv", "servantship")) {
                    assertEquals("$term！", correct(source, "$term！", locale))
                }
                for (source in listOf("サーバント", "さーばんと", "Servants")) {
                    assertEquals("${term}和僕人！", correct(source, "${term}和僕人！", locale))
                    assertEquals("從者，$term！", correct(source, "從者，$term！", locale))
                    assertEquals("$term！", correct("$source、サーヴァント！", "$term！", locale))
                    assertEquals("$expected，$expected！", correct("$source、サーヴァント！", "從者，$term！", locale))
                    assertEquals("$term！", correct(source, "$term！", "en"))
                }
            }
        }
    }

    @Test
    fun `English plural is one recognized term not two occurrences`() {
        assertEquals("從者！", correct("Servants", "侍者！", "zh-Hant"))
        assertEquals("僕役和隨從！", correct("Servants", "僕役和隨從！", "zh-Hant"))
        assertEquals("從者和從者！", correct("Servants、Servant", "僕役和隨從！", "zh-Hant"))
    }

    @Test
    fun `new Servant aliases retain independent partial final and Master handling`() {
        assertEquals("侍者！", correct("サーバン", "侍者！", "zh-Hant"))
        assertEquals("從者！", correct("サーバント", "侍者！", "zh-Hant"))
        assertEquals("侍者！", correct("", "侍者！", "zh-Hant"))
        for (source in listOf("Master、さーばんと。", "ﾏｽﾀｰ、Servants。")) {
            val corrected = correct(source, "師父，隨從。", "zh-Hant")
            assertEquals("御主，從者。", corrected)
            assertEquals(corrected, correct(source, corrected, "zh-Hant"))
            assertEquals("師父和主人，從者。", correct(source, "師父和主人，侍者。", "zh-Hant"))
            assertEquals("御主，僕役和隨從。", correct(source, "師父，僕役和隨從。", "zh-Hant"))
        }
    }

    @Test
    fun `new Servant translations preserve whitespace and wider meanings`() {
        assertEquals(
            "  從者……\n『從者』！  ",
            correct("ｻｰﾊﾞﾝﾄ、Servants。", "  僕從……\n『侍者』！  ", "zh-Hant")
        )
        for (locale in listOf("zh-Hans", "zh-Hant")) {
            for (term in listOf("主人", "師父", "奴隸", "奴隶", "服務員", "服务员", "使用者")) {
                assertEquals("$term！", correct("サーバント", "$term！", locale))
                assertEquals("$term！", correct("Servants", "$term！", locale))
            }
        }
    }

    private fun correct(source: String, text: String, locale: String = "zh-Hans") =
        LiveSubtitleTerminology.correct(source, text, locale)
}
