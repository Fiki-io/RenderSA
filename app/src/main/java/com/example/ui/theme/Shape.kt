package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

object RenderSAShapes {
    val Card = RoundedCornerShape(RenderSADimens.CardRadius)
    val Modal = RoundedCornerShape(RenderSADimens.ModalRadius)
    val TopModal = RoundedCornerShape(
        topStart = RenderSADimens.ModalRadius,
        topEnd = RenderSADimens.ModalRadius
    )
    val Button = RoundedCornerShape(RenderSADimens.ButtonRadius)
    val Input = RoundedCornerShape(RenderSADimens.InputRadius)
    val Tag = RoundedCornerShape(RenderSADimens.TagRadius)

    val MaterialShapes = Shapes(
        small = Tag,
        medium = Button,
        large = Card,
        extraLarge = Modal
    )
}
