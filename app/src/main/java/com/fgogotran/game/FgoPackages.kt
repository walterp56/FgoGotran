package com.fgogotran.game

object FgoPackages {
    const val JP = "com.aniplex.fategrandorder"

    val exactNames = setOf(
        JP,
        "com.aniplex.fategrandorder.en",
        "com.bilibili.fatego",
        "com.bilibili.fategp",
        "com.bilibili.fatego.sharejoy",
        "com.bilibili.fgo.mi",
        "com.bilibili.fgo.uc",
        "com.xiaomeng.fategrandorder",
        "com.komoe.fgo"
    )

    private val prefixes = setOf(
        "$JP.",
        "com.bilibili.fatego.",
        "com.bilibili.fgo.",
        "com.xiaomeng.fategrandorder.",
        "com.komoe.fgo."
    )

    fun isSupported(packageName: String): Boolean {
        return packageName in exactNames || prefixes.any(packageName::startsWith)
    }
}
