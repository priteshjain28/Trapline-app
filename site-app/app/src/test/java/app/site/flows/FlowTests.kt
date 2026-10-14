package app.site.flows

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextClearance
import kotlinx.coroutines.flow.first
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
        assertAccessible("S07 reports")
        // Robolectric has no native PdfDocument: a new document is born closed. Anything else is a real failure.
        // The PDF itself is checked on a phone (manual checklist).
        val why = org.robolectric.shadows.ShadowLog.getLogsForTag("ReportService").mapNotNull { it.throwable }
        org.junit.Assume.assumeFalse("PdfDocument unavailable under Robolectric",
            why.isNotEmpty() && why.all { it.message == "document is closed!" && it.stackTrace.any { f -> f.methodName == "<init>" } })
        assertTrue("the PDF was made; ${why.map { it.stackTraceToString().take(1500) }}", showsDesc("Open"))
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
        tap(compose.onAllNodes(androidx.compose.ui.test.hasText("Open task") and androidx.compose.ui.test.hasClickAction())[0])
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

/** F03/F04: material in, material out, stock right. Seed: Cement 135 bag on Green Park Tower B. */
class MaterialFlows : FlowTest() {
    private fun openMaterial() {
        launch()
        tap(text("Green Park Tower B"))
        tap(tab("Material"))
        waitFor("135 bag on site")
    }

    private fun pickMaterial(label: String) {
        tap(named("Material: Pick material"))
        waitFor("Pick material")
        tap(text(label))
        compose.waitForIdle()
    }

    @Test fun m_H1_stockListShowsWhatIsOnSite() {
        openMaterial()
        assertTrue(shows("Red bricks"))
        assertTrue(shows("2800 nos on site"))
        assertAccessible("S15 on site")
        tap(text("TMT bar"))
        waitFor("History")
        assertTrue("delivery in the history", shows("Received · delivery #2"))
        assertAccessible("S16 material history")
    }

    @Test fun m_H2_addMaterialAlreadyOnSite() {
        openMaterial()
        tap(button("Add material"))
        type("Material", "Binding wire")
        tap(chip("kg"))
        type("How much is on site now", "25")
        assertAccessible("add material")
        tap(button("Add material"))
        waitFor("25 kg on site")
    }

    @Test fun m_H3_recordADelivery() {
        openMaterial()
        tap(tab("Buying"))
        tap(tab("Received"))
        waitFor("Delivery #2", substring = true)
        tap(button("Record delivery"))
        pickMaterial("Cement · OPC 53")
        type("Quantity", "50")
        type("Challan or bill number", "CH-1301")
        assertAccessible("S14 record delivery")
        tap(button("Save"))
        waitFor("Delivery #3", substring = true)
        tap(tab("Stock"))
        waitFor("185 bag on site")
    }

    @Test fun m_H4_issueToATask() {
        openMaterial()
        tap(tab("Issued"))
        waitFor("Issue #2", substring = true)
        tap(button("Issue material"))
        tap(named("For task: Pick a task"))
        tap(text("#2 Brickwork level 3"))
        pickMaterial("Cement · OPC 53")
        type("Quantity", "35")
        assertAccessible("S19 issue material")
        tap(button("Issue material"))
        waitFor("Issue #3", substring = true)
        assertTrue(shows("For #2 Brickwork level 3", substring = true))
        tap(tab("On site"))
        waitFor("100 bag on site")
    }

    @Test fun m_E1_cantIssueMoreThanOnSite() {
        openMaterial()
        tap(tab("Issued"))
        tap(button("Issue material"))
        tap(named("For task: Pick a task"))
        tap(text("#2 Brickwork level 3"))
        pickMaterial("Cement · OPC 53")
        type("Quantity", "500")
        assertTrue(shows("That's more than the site holds"))
        button("Issue material").assertIsNotEnabled()
    }

    @Test fun m_E2_countRecordsTheDifference() {
        openMaterial()
        tap(compose.onAllNodes(androidx.compose.ui.test.hasText("Update") and androidx.compose.ui.test.hasClickAction())[0].let { it })
        waitFor("Update Cement · OPC 53")
        tap(tab("Counted"))
        type("Counted", "130")
        tap(button("Save"))
        waitFor("130 bag on site")
        tap(text("Cement"))
        waitFor("Count corrected")
        assertTrue(shows("−5 bag"))
    }

    @Test fun m_N1_deliveryNeedsMaterialAndQuantity() {
        openMaterial()
        tap(tab("Buying"))
        tap(tab("Received"))
        tap(button("Record delivery"))
        button("Save").assertIsNotEnabled()
        pickMaterial("River sand")
        button("Save").assertIsNotEnabled()
        type("Quantity", "0")
        assertTrue(shows("Enter a number above zero"))
        button("Save").assertIsNotEnabled()
    }
}

/** F09: site photos and albums. */
class GalleryFlows : FlowTest() {
    private fun openGallery() {
        launch()
        tap(text("Green Park Tower B"))
        tap(tab("More"))
        tap(button("Photos"))
        waitFor("No photos yet")
    }

    @Test fun g_H1_emptyGalleryThenAnAlbum() {
        openGallery()
        assertAccessible("S30 photos")
        tap(tab("Albums"))
        waitFor("No albums yet")
        tap(compose.onAllNodes(androidx.compose.ui.test.hasText("New album") and androidx.compose.ui.test.hasClickAction())[0])
        type("Album name", "Slab 4 pour")
        tap(button("Create album"))
        waitFor("Slab 4 pour")
        assertTrue("inside the new album", shows("Add photos to this album, or move them in from the gallery."))
        assertAccessible("S30 album")
    }

    @Test fun g_H2_photoViewerAndMoveToAlbum() {
        openGallery()
        kotlinx.coroutines.runBlocking {
            val p = container.projects.projects().first().first { it.name == "Green Park Tower B" }
            container.gallery.createAlbum(p.id, "Lobby").getOrThrow()
            container.gallery.addPhotos(p.id, listOf("content://test/photo-1")).getOrThrow()
        }
        compose.waitUntil(5_000) { showsDesc("Photo, 13 Oct") }
        tap(action("Photo, 13 Oct"))
        waitFor("Added to the site")
        tap(button("Move to album"))
        tap(text("Lobby"))
        compose.waitForIdle()
        tap(tab("Albums"))
        waitFor("1 photo")
    }
}
