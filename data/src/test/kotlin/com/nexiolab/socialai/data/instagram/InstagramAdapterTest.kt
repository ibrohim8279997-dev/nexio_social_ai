package com.nexiolab.socialai.data.instagram

import com.nexiolab.socialai.domain.instagram.ContainerKind
import com.nexiolab.socialai.domain.instagram.ContainerStatus
import com.nexiolab.socialai.domain.instagram.InstagramException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class InstagramAdapterTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().readTimeout(1000, TimeUnit.MILLISECONDS).build()
    private val api get() = GraphInstagramApi(client, server.url("").toString().trimEnd('/'), server.url("").toString().trimEnd('/'))

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test fun reelContainerUsesOfficialParametersAndHeaderToken() = runTest {
        respond(200, """{"id":"c1"}""")
        assertEquals("c1", api.createContainer("tok", "ig1", ContainerKind.REEL, "https://h/v.mp4", "cap"))
        val sent = server.takeRequest()
        assertTrue(sent.url.encodedPath.endsWith("/ig1/media"))
        assertEquals("Bearer tok", sent.headers["Authorization"])
        assertFalse("tok" in sent.url.toString())
        val form = sent.body!!.utf8()
        assertTrue(form, "media_type=REELS" in form && "video_url" in form)
    }

    @Test fun carouselChildIsAnImageContainerMarkedAsCarouselItem() = runTest {
        respond(200, """{"id":"k1"}""")
        assertEquals("k1", api.createCarouselItem("tok", "ig1", "https://h/a.jpg"))
        val sent = server.takeRequest()
        assertTrue(sent.url.encodedPath.endsWith("/ig1/media"))
        assertEquals("Bearer tok", sent.headers["Authorization"])
        assertFalse("tok" in sent.url.toString())
        val form = sent.body!!.utf8()
        assertTrue(form, "image_url=https%3A%2F%2Fh%2Fa.jpg" in form && "is_carousel_item=true" in form)
        assertFalse(form, "media_type" in form)
    }

    @Test fun carouselParentListsTheChildrenInOrderWithTheCaption() = runTest {
        respond(200, """{"id":"p1"}""")
        assertEquals("p1", api.createCarousel("tok", "ig1", listOf("k3", "k1", "k2"), "hello"))
        val sent = server.takeRequest()
        assertTrue(sent.url.encodedPath.endsWith("/ig1/media"))
        val form = sent.body!!.utf8()
        assertTrue(form, "media_type=CAROUSEL" in form && "children=k3%2Ck1%2Ck2" in form && "caption=hello" in form)
    }

    @Test fun carouselCallsKeepTheDeliveredSemantics() = runTest {
        respond(400, """{"error":{"message":"bad image","code":9004}}""")
        try {
            api.createCarouselItem("t", "ig", "https://h/a.jpg")
            fail("expected failure")
        } catch (e: InstagramException) {
            assertTrue(e.delivered)
        }
        respond(502, "bad gateway")
        try {
            api.createCarousel("t", "ig", listOf("a", "b"), "c")
            fail("expected failure")
        } catch (e: InstagramException) {
            assertFalse(e.delivered)
        }
        respond(400, """{"error":{"message":"expired","code":190}}""")
        try {
            api.createCarousel("t", "ig", listOf("a", "b"), "c")
            fail("expected failure")
        } catch (e: InstagramException) {
            assertTrue(e.authFailure)
        }
    }

    @Test fun containerStatusIsMapped() = runTest {
        respond(200, """{"status_code":"PUBLISHED"}""")
        assertEquals(ContainerStatus.PUBLISHED, api.containerState("t", "c").status)
    }

    @Test fun clientErrorMeansInstagramAnswered() = runTest {
        respond(400, """{"error":{"message":"bad media","code":100}}""")
        try {
            api.publish("t", "ig", "c")
            fail("expected failure")
        } catch (e: InstagramException) {
            assertTrue(e.delivered)
            assertFalse(e.authFailure)
        }
    }

    @Test fun serverErrorAndTimeoutAreNotConsideredDelivered() = runTest {
        respond(502, "bad gateway")
        try {
            api.publish("t", "ig", "c")
            fail("expected failure")
        } catch (e: InstagramException) {
            assertFalse(e.delivered)
        }
        server.enqueue(MockResponse.Builder().bodyDelay(2, TimeUnit.SECONDS).body("{}").build())
        try {
            api.publish("t", "ig", "c")
            fail("expected failure")
        } catch (e: InstagramException) {
            assertFalse(e.delivered)
        }
    }

    @Test fun requestTimeoutIsNotConsideredDelivered() = runTest {
        respond(408, """{"error":{"message":"timeout","code":2}}""")
        try {
            api.publish("t", "ig", "c")
            fail("expected failure")
        } catch (e: InstagramException) {
            assertFalse("a 408 proves nothing about whether the post went out", e.delivered)
        }
    }

    @Test fun expiredTokenIsRecognised() = runTest {
        respond(400, """{"error":{"message":"expired","code":190}}""")
        try {
            api.profile("t")
            fail("expected failure")
        } catch (e: InstagramException) {
            assertTrue(e.authFailure)
        }
    }

    /** The GET Object example from the AWS Signature Version 4 documentation, with its published signature. */
    @Test fun signatureMatchesAwsTestVector() {
        val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val headers = SigV4.sign(
            "GET", "examplebucket.s3.amazonaws.com", "/test.txt", "us-east-1",
            "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", empty, null,
            ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC),
            extraSignedHeaders = mapOf("range" to "bytes=0-9"),
        )
        val auth = headers.getValue("Authorization")
        assertTrue(auth, auth.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"))
    }
}
