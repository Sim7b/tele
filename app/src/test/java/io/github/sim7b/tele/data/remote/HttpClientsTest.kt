package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.UserFacingException
import org.junit.Test

class HttpClientsTest {

    @Test
    fun `accepts regular JSON`() {
        HttpClients.checkJsonDepth("""{"a":[{"b":[1,2,{"c":"[[[[["}]}]}""", maxDepth = 5)
    }

    @Test(expected = UserFacingException::class)
    fun `rejects deeply nested JSON`() {
        HttpClients.checkJsonDepth("[".repeat(100_000))
    }

    @Test
    fun `ignores brackets inside strings`() {
        HttpClients.checkJsonDepth("""["\"[[[[[[[[[[", "{{{{{{{{{{"]""", maxDepth = 1)
    }

    @Test(expected = UserFacingException::class)
    fun `rejects single-quoted strings that would hide brackets`() {
        HttpClients.checkJsonDepth("['\"', " + "[".repeat(100) + "]", maxDepth = 64)
    }

    @Test(expected = UserFacingException::class)
    fun `rejects comments`() {
        HttpClients.checkJsonDepth("[/* \" */ 1]")
    }

    @Test(expected = UserFacingException::class)
    fun `rejects unbalanced closing brackets`() {
        HttpClients.checkJsonDepth("]]][[[")
    }
}
