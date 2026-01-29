package com.sciencepixel.controller

import com.sciencepixel.service.YoutubeService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class OAuthController(
    private val youtubeService: YoutubeService
) {

    @GetMapping("/callback")
    fun handleCallback(@RequestParam("code") code: String?): String {
        return if (!code.isNullOrEmpty()) {
            youtubeService.exchangeCodeForToken(code, "http://localhost:8080/callback")
            "<h1>Authorization Successful!</h1><p>You can close this window and return to the application logs.</p>"
        } else {
            "<h1>Authorization Failed</h1><p>No code received.</p>"
        }
    }
}
