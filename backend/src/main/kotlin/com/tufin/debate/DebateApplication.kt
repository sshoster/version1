package com.tufin.debate

import io.mongock.runner.springboot.EnableMongock
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableMongock
@EnableScheduling
class DebateApplication

fun main(args: Array<String>) {
    runApplication<DebateApplication>(*args)
}
