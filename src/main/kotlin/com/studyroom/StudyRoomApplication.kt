package com.studyroom

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class StudyRoomApplication

fun main(args: Array<String>) {
    runApplication<StudyRoomApplication>(*args)
}
