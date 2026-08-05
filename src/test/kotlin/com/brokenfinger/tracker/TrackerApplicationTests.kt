package com.brokenfinger.tracker

import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest
class TrackerApplicationTests {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `application context loads`() {
        context.containsBean("trackerApplication").shouldBeTrue()
    }
}
