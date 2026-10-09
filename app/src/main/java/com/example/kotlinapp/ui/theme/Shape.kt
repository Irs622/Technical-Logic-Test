package com.example.kotlinapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val ShapeSm = RoundedCornerShape(4.dp)
val ShapeMd = RoundedCornerShape(8.dp)

val TokoShapes = Shapes(
    extraSmall = ShapeSm, small = ShapeSm, medium = ShapeMd, large = ShapeMd, extraLarge = ShapeMd,
)
