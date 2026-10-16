package com.fgogotran.chapter.install

import kotlin.test.*

class ChapterGameProcessTableTest {
    @Test fun `game subprocess also blocks writes regardless of process name`() {
        assertFalse(gameStoppedFromProcessTable(" UID PID NAME\n2000 100 ps\n10075 200 game:child\n", 10075))
    }
    @Test fun `valid table without game uid reports stopped`() {
        assertTrue(gameStoppedFromProcessTable(" UID PID NAME\n2000 100 ps\n1000 200 system_server\n", 10075))
    }
    @Test fun `empty header only truncated non numeric and changed format tables fail closed`() {
        listOf("", "UID PID NAME\n", "USER PID NAME\nshell 10 ps\n", "UID PID NAME\n2000 10\n", "UID PID NAME\nshell 10 ps\n",
            "UID PID NAME\n1000 20 system_server\n", "UID PID NAME\n2000 -1 ps\n").forEach {
            assertFails { gameStoppedFromProcessTable(it, 10075) }
        }
    }
}
