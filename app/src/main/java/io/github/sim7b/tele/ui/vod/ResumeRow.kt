package io.github.sim7b.tele.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.ui.components.FadeInImage
import io.github.sim7b.tele.ui.components.ProgressLine
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.components.focusRing
import io.github.sim7b.tele.ui.components.touchClick
import io.github.sim7b.tele.ui.theme.SurfaceCard
import io.github.sim7b.tele.ui.theme.TextPrimary

/** Movies and series started on this TV, on the live TV home: a wide card each, its sheet opened on OK. */
@Composable
fun ResumeRow(items: List<Pair<VodTitle, VodProgress>>, onOpen: (VodTitle) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionLabel(text = stringResource(R.string.home_resume))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 10.dp, end = 24.dp)
        ) {
            items(items, key = { it.first.key }) { (title, progress) ->
                ResumeCard(title = title, progress = progress.fraction, onClick = { onOpen(title) })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResumeCard(title: VodTitle, progress: Float, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .width(ResumeCardWidth)
            .height(ResumeCardWidth * 9 / 16)
            .touchClick(onClick),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = focusRing(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = SurfaceCard, focusedContainerColor = SurfaceCard)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            FadeInImage(url = title.backdropUrl ?: title.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
            )
            Text(
                text = title.title,
                fontSize = 13.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
            )
            ProgressLine(
                progress = progress,
                height = 3.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )
        }
    }
}

private val ResumeCardWidth = 220.dp
