package com.example.mine_detector

import android.graphics.RectF

data class Detection(
    val boundingBox: RectF,
    val categories: List<Category>
)

data class Category(
    val label: String,
    val score: Float
)
