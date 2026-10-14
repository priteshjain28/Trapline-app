package app.site.flows

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextClearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** F10: sign in, profile, settings. */
class SignInFlows : FlowTest() {
    @Test fun f10_H1_newPhoneSignsIn() {
        launch(signedIn = false)
        type("Mobile number", "98765 43210")
        tap(button("Send code"))
        type("6-digit code", "123456")
        tap(button("Verify"))
        waitFor("What should your team call you?")
        type("Your name", "Anita Rao")
        tap(button("Continue"))
        waitFor("Green Park Tower B")
        assertAccessible("S04 projects")
    }

    @Test fun f10_N1_wrongCodeIsExplained() {
        launch(signedIn = false)
        type("Mobile number", "9876543210")
        tap(button("Send code"))
        type("6-digit code", "000000")
        tap(button("Verify"))
        waitFor("That code didn't match", substring = true)
        assertTrue("still on the code step", shows("Enter the code"))
    }

    @Test fun f10_E1_shortNumberCantSend() {
        launch(signedIn = false)
        type("Mobile number", "98765 4321")
        button("Send code").assertIsNotEnabled()
        assertAccessible("S01 sign in")
    }

    @Test fun f10_H2_renameAndSignOut() {
        launch()
        tap(action("Your profile"))
        waitFor("Profile")
        field("Your name").performTextClearance()
        type("Your name", "Anita R.")
        tap(button("Save"))
        assertEquals("Anita R.", container.session.session.value?.user?.name)
        assertAccessible("S28 profile")
        tap(button("Sign out"))
        waitFor("Sign in with your phone")
    }

    @Test fun f10_H3_shiftLengthSaves() {
        launch()
        tap(text("Green Park Tower B"))
        tap(tab("More"))
        tap(button("Attendance settings"))
        waitFor("8 h")
        tap(named("Longer shift"))
        waitFor("8.5 h")
        assertAccessible("S24 attendance settings")
    }

    @Test fun f10_H4_newProjectOpens() {
        launch()
        tap(button("New project"))
        type("Project name", "Riverside Row Houses")
        tap(button("Create project"))
        waitFor("Riverside Row Houses")
        assertTrue("lands inside the new project", shows("Create a report"))
    }

    @Test fun f10_N2_emptyProjectNameCantSave() {
        launch()
        tap(button("New project"))
        button("Create project").assertIsNotEnabled()
        type("Project name", "   ")
        button("Create project").assertIsNotEnabled()
        assertAccessible("S03 new project")
    }
}

/** F07: plan and track tasks. F01: the site engineer logs the day. */
class TaskFlows : FlowTest() {
    private fun openTasks() {
        launch()
        tap(text("Green Park Tower B"))
        tap(tab("Tasks"))
    }

    @Test fun f07_H1_createTaskWithQuantity() {
        openTasks()
        tap(button("Add task"))
        type("Task name", "Shuttering slab 4")
        type("Planned quantity", "100")
        type("Unit", "m3")
        tap(button("Add task"))
        waitFor("#5 Shuttering slab 4")
        assertTrue(shows("0 of 100 m3 done"))
    }

    @Test fun f07_N1_taskNeedsAName() {
        openTasks()
        tap(button("Add task"))
        button("Add task").assertIsNotEnabled()
        type("Planned quantity", "-5")
        assertTrue("a negative quantity is refused", shows("Enter a number above zero"))
        assertAccessible("S10 new task")
    }

    @Test fun f07_E1_searchByNumberAndNoMatch() {
        openTasks()
        type("Search tasks by number or name", "#2")
        assertTrue(shows("#2 Brickwork level 3"))
        assertFalse(shows("#3 Plaster level 2"))
        field("Search tasks by number or name").performTextClearance()
        type("Search tasks by number or name", "zzz")
        waitFor("No tasks match")
        tap(button("Clear filters"))
        waitFor("#1 Excavation and PCC")
    }

    @Test fun f07_E2_delayedFilter() {
        openTasks()
        tap(chip("Delayed"))
        assertTrue(shows("#3 Plaster level 2"))
        assertFalse(shows("#2 Brickwork level 3"))
        assertAccessible("S08 tasks")
    }

    @Test fun f07_E3_longNameDoesNotBreakTheList() {
        openTasks()
        tap(button("Add task"))
        val long = "Waterproofing of the basement retaining wall, grid A to F"   // 58 chars
        type("Task name", long)
        tap(button("Add task"))
        waitFor(long, substring = true)
    }

    @Test fun f07_E4_backReturnsToTheSameTab() {
        openTasks()
        tap(text("#2 Brickwork level 3"))
        waitFor("Log today's work")
        tap(named("Back"))
        waitFor("#2 Brickwork level 3")
        assertTrue("still on Tasks, not Report", !shows("Create a report"))
    }

    @Test fun f01_H1_logQuantity() {
        openTasks()
        tap(text("#2 Brickwork level 3"))
        waitFor("325 of 600 m2 done")
        type("Quantity done today", "25")
        scrollTo("Save update")
        tap(button("Save update"))
        waitFor("Update saved")
        scrollTo("350 of 600 m2 done")
        assertTrue(shows("58%"))
        assertAccessible("S09 task detail")
    }

    @Test fun f01_E1_percentOver100Refused() {
        openTasks()
        tap(text("#4 Electrical conduits level 3"))
        waitFor("Progress is logged in percent")
        type("Progress now", "150")
        assertTrue(shows("Enter a number above zero"))
        scrollTo("Save update")
        button("Save update").assertIsNotEnabled()
    }

    @Test fun f01_E2_remarksKeepEmojiAndIndianScripts() {
        openTasks()
        tap(text("#2 Brickwork level 3"))
        val note = "Café ठीक है ✅ 2nd coat"
        type("Remarks", note)
        scrollTo("Save update")
        tap(button("Save update"))
        waitFor("Update saved")
        scrollTo(note)
        assertTrue(shows(note))
    }

    @Test fun f01_N1_emptyUpdateCantSave() {
        openTasks()
        tap(text("#2 Brickwork level 3"))
        scrollTo("Save update")
        button("Save update").assertIsNotEnabled()
    }
}

/** F04: mark attendance and see wages. */
class AttendanceFlows : FlowTest() {
    private fun openAttendance() {
        launch()
        tap(text("Green Park Tower B"))
        tap(tab("Attendance"))
        waitFor("On site: 0 of 5")
    }

    @Test fun f04_H1_markOne() {
        openAttendance()
        tap(named("Suresh Kumar: present"))
        waitFor("On site: 1 of 5")
        assertAccessible("S21 register")
    }

    @Test fun f04_H2_markTheRest() {
        openAttendance()
        tap(named("Imran Khan: absent"))
        tap(button("Mark rest present"))
        waitFor("On site: 4 of 5")
        button("Mark rest present").assertIsNotEnabled()
    }

    @Test fun f04_E1_noFutureDays() {
        openAttendance()
        named("Next day").assertIsNotEnabled()
        tap(named("Previous day"))
        waitFor("Mon, 12 Oct 2026")
        named("Next day").assertIsEnabled()
    }

    @Test fun f04_E2_overtimeAndNote() {
        openAttendance()
        tap(named("Suresh Kumar: present"))
        tap(text("Suresh Kumar"))
        waitFor("Overtime")
        type("Overtime", "2")
        type("Work note", "Shuttering")
        tap(button("Save"))
        waitFor("OT 2 h", substring = true)
        assertTrue(shows("“Shuttering”", substring = true))
    }

    @Test fun f04_E3_overtimeOver16Refused() {
        openAttendance()
        tap(text("Suresh Kumar"))
        type("Overtime", "20")
        assertTrue(shows("Enter between 0 and 16 hours"))
        button("Save").assertIsNotEnabled()
    }

    @Test fun f04_H3_wagesFromAttendance() {
        openAttendance()
        tap(tab("Wages"))
        waitFor("Last 7 days", substring = true)
        assertTrue("seeded attendance has wages", shows("Suresh Kumar"))
        assertFalse("not zero", shows("₹0"))
    }

    @Test fun f04_H4_addWorker() {
        openAttendance()
        tap(button("Add worker"))
        type("Name", "Pooja Shinde")
        type("Daily wage", "650")
        tap(button("Add worker"))
        waitFor("On site: 0 of 6")
        assertTrue(shows("₹650/day", substring = true))
    }

    @Test fun f04_N1_wageMustBeANumber() {
        openAttendance()
        tap(button("Add worker"))
        type("Name", "Pooja Shinde")
        type("Daily wage", "six fifty")
        assertTrue(shows("Enter an amount in rupees, e.g. 800"))
        button("Add worker").assertIsNotEnabled()
        assertAccessible("S22 add worker")
    }
}

/** F02: the owner gets a report. F08: the feed. */
class ReportAndFeedFlows : FlowTest() {
    private fun openProject() {
        launch()
        tap(text("Green Park Tower B"))
    }

    @Test fun f02_H1_detailedReportForToday() {
        openProject()
        assertAccessible("S06 report builder")
        tap(button("Create report"))
        waitFor("Detailed · 13 Oct 2026")
        compose.waitUntil(15_000) { showsDesc("Open") || shows("Couldn't make it") }
        assertTrue("the PDF was made", showsDesc("Open"))
        assertAccessible("S07 reports")
    }

    @Test fun f02_E1_nothingToIncludeCantCreate() {
        openProject()
        tap(chip("Work done"))
        tap(chip("Labour"))
        button("Create report").assertIsNotEnabled()
    }

    @Test fun f02_E2_customPeriodShowsDates() {
        openProject()
        tap(chip("Pick dates"))
        assertTrue(shows("From") && shows("To"))
    }

    @Test fun f08_H1_feedOpensTheTask() {
        openProject()
        tap(tab("More"))
        assertAccessible("S25 more")
        tap(button("Activity"))
        waitFor("Ravi Singh logged progress")
        assertAccessible("S27 feed")
        compose.onAllNodes(androidx.compose.ui.test.hasText("Open task") and androidx.compose.ui.test.hasClickAction())[0].performClick()
        compose.waitForIdle()
        waitFor("Log today's work")
    }
}

/** Layout at the edges: a narrow phone, and 200% text. Screenshots go to the manual pass. */
class LayoutEdges : FlowTest() {
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    @Test fun narrowPhone() {
        launch()
        tap(text("Green Park Tower B"))
        tap(tab("Attendance"))
        waitFor("On site: 0 of 5")
        capture("narrow-360dp-attendance")
        assertTrue(shows("Suresh Kumar"))
    }

    @Test fun largeText() {
        RuntimeEnvironment.setFontScale(2f)
        launch()
        waitFor("Green Park Tower B")
        capture("font-200-projects")
        tap(text("Green Park Tower B"))
        tap(tab("Tasks"))
        capture("font-200-tasks")
        assertTrue(shows("#2 Brickwork level 3"))
    }
}
