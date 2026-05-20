package com.sakurafubuki.yume.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val AppShapes = Shapes(
    extraSmall = ShapeDefaults.ExtraSmall,
    small = ShapeDefaults.Small,
    medium = ShapeDefaults.Medium,
    large = RoundedCornerShape(16.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)
