package com.opaque.healthsynctester.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * PACT Consistent Rounded Shapes.
 *
 * One primary corner radius (16dp) for cards, dialogs, buttons, and text fields;
 * pill shape for status chips.
 */
object PactShapes {
    val cardRadius = 16.dp
    val buttonRadius = 16.dp
    val inputRadius = 16.dp
    val chipRadius = 24.dp

    val card = RoundedCornerShape(cardRadius)
    val button = RoundedCornerShape(buttonRadius)
    val input = RoundedCornerShape(inputRadius)
    val chip = RoundedCornerShape(chipRadius)
}
