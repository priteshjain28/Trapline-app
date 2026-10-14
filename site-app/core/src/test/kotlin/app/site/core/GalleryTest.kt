package app.site.core

import app.site.core.data.AppError
import app.site.core.data.fake.FakeBackend
import app.site.core.model.Album
import app.site.core.model.NewProject
import app.site.core.model.NewTask
import app.site.core.model.NewTaskUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GalleryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-14T06:30:00Z"), ZoneId.of("Asia/Kolkata"))

    @Test fun galleryHasUpdatePhotosAndAddedOnes() = runTest {
        val b = FakeBackend(clock, latencyMs = 0, seed = false)
        b.verifyOtp("9812345678", FakeBackend.DEMO_OTP).getOrThrow()
        val p = b.create(NewProject("Tower")).getOrThrow()
        val t = b.create(NewTask(p.id, "Brickwork")).getOrThrow()
        b.addUpdate(NewTaskUpdate(t.id, b.today(), remarks = "East wall", photos = listOf("content://u1"))).getOrThrow()
        assertEquals(2, b.addPhotos(p.id, listOf("content://g1", "content://g2")).getOrThrow())

        val photos = b.photos(p.id).first()
        assertEquals(3, photos.size)
        assertEquals(t.id, photos.single { it.ref == "content://u1" }.taskId, "update photos know their task")
        assertEquals(listOf("content://g2", "content://g1"), photos.filter { it.taskId == null }.map { it.ref }, "newest first")

        val album = b.createAlbum(p.id, "  Slab   pour ").getOrThrow()
        assertEquals("Slab pour", album.name)
        assertEquals(album, b.createAlbum(p.id, "slab POUR").getOrThrow(), "same name, same album")
        assertEquals(Album.idFor(p.id, "Slab pour"), album.id)
        b.moveToAlbum(photos.map { it.id }, album.id).getOrThrow()
        val a = b.albums(p.id).first().single()
        assertEquals(3, a.photoCount)
        b.moveToAlbum(listOf(photos.first().id), null).getOrThrow()
        assertEquals(2, b.albums(p.id).first().single().photoCount)
        assertNull(b.photos(p.id).first().first { it.id == photos.first().id }.albumId)

        b.addPhotos(p.id, listOf("content://g3"), album.id).getOrThrow()
        assertEquals(3, b.albums(p.id).first().single().photoCount)
    }

    @Test fun refusals() = runTest {
        val b = FakeBackend(clock, latencyMs = 0, seed = false)
        b.verifyOtp("9812345678", FakeBackend.DEMO_OTP).getOrThrow()
        val p = b.create(NewProject("Tower")).getOrThrow()
        assertIs<AppError.Invalid>(b.createAlbum(p.id, "   ").exceptionOrNull())
        assertIs<AppError.Invalid>(b.createAlbum(p.id, "x".repeat(61)).exceptionOrNull())
        assertIs<AppError.Invalid>(b.addPhotos(p.id, emptyList()).exceptionOrNull())
        assertIs<AppError.NotAllowed>(b.addPhotos(p.id, listOf("content://x"), albumId = "nope").exceptionOrNull())
        assertTrue(b.photos(p.id).first().isEmpty())
    }
}

class TeamTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-14T06:30:00Z"), ZoneId.of("Asia/Kolkata"))

    @Test fun demoTeamAndInvites() = runTest {
        val b = FakeBackend(clock, latencyMs = 0)
        b.verifyOtp("9812345678", FakeBackend.DEMO_OTP).getOrThrow()
        b.completeProfile("Anita Rao", null).getOrThrow()
        val tower = b.projects().first().first { it.name == "Green Park Tower B" }
        val team = b.team(tower.id).first()
        assertTrue(team.canManage)
        assertEquals(listOf("Anita Rao", "Ravi Singh", "Meena Iyer"), team.members.map { it.name })
        assertEquals(app.site.core.model.ProjectRole.ENGINEER, team.members[1].role)

        assertEquals(AppError.Reason.ALREADY_MEMBER,
            (b.invite(tower.id, "+919800000001", app.site.core.model.ProjectRole.VIEWER).exceptionOrNull() as AppError.Invalid).reason)
        assertEquals(AppError.Reason.BAD_PHONE,
            (b.invite(tower.id, "12345", app.site.core.model.ProjectRole.VIEWER).exceptionOrNull() as AppError.Invalid).reason)
        b.invite(tower.id, "98111 22222", app.site.core.model.ProjectRole.STOREKEEPER).getOrThrow()
        assertEquals("+919811122222", b.team(tower.id).first().invites.single().phone)

        val ravi = team.members[1].userId
        b.setRole(tower.id, ravi, app.site.core.model.ProjectRole.MANAGER).getOrThrow()
        assertEquals(app.site.core.model.ProjectRole.MANAGER, b.team(tower.id).first().members.first { it.userId == ravi }.role)
        b.remove(tower.id, ravi).getOrThrow()
        assertTrue(b.team(tower.id).first().members.none { it.userId == ravi })
        // the owner's own row comes from the company and can't be edited per site
        assertIs<AppError.NotAllowed>(b.setRole(tower.id, team.members[0].userId, app.site.core.model.ProjectRole.VIEWER).exceptionOrNull())
    }
}

class CategoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-14T06:30:00Z"), ZoneId.of("Asia/Kolkata"))

    @Test fun mergeRules() {
        assertEquals(listOf("General", "civil", "Masonry"),
            app.site.core.logic.Categories.merge(listOf("civil", " Masonry "), listOf("Civil", "General", "")))
    }

    @Test fun companyListGrowsAndStaysUnique() = runTest {
        val b = FakeBackend(clock, latencyMs = 0)
        b.verifyOtp("9812345678", FakeBackend.DEMO_OTP).getOrThrow()
        val start = b.categories().first()
        assertEquals("General", start.first())
        assertTrue("Finishing" in start && "MEP" in start, "common ones and ones on seed tasks")
        assertEquals("Formwork", b.addCategory("  Formwork ").getOrThrow())
        assertEquals("Formwork", b.addCategory("formwork").getOrThrow(), "same name, any case: the first spelling")
        assertEquals("Civil", b.addCategory("CIVIL").getOrThrow())
        assertEquals(start.size + 1, b.categories().first().size)
        assertIs<AppError.Invalid>(b.addCategory(" ").exceptionOrNull())
    }
}
