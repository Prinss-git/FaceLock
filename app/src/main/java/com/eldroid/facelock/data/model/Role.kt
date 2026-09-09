package com.eldroid.facelock.data.model

enum class Role {
    ADMIN,
    USER,
    SECURITY;

    companion object {
        fun from(value: String?): Role = when (value?.uppercase()) {
            "ADMIN" -> ADMIN
            "SECURITY" -> SECURITY
            else -> USER
        }
    }
}
