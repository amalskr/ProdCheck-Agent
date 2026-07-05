package com.example.bad

// Intentionally bad file to verify the Layer 1 rules fire.
class BadExampleActivity {
    val apiKey = "AIzaSyD4-fakekey1234567890abcdefghijklmn" // HardcodedSecret

    fun crashRisk(name: String?) {
        val length = name!!.length // DoubleBang
        println("user token = abc123token") // SensitiveLog
    }

    fun freezeUi() {
        Thread.sleep(3000) // MainThreadBlocking
    }
}
