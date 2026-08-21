package com.kitsuneandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
internal fun AnimeExtrasSection(
    extras: AnimeExtras,
    loading: Boolean,
    onAnime: (Anime) -> Unit
) {
    if (loading) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        extras.trailer?.let { trailer ->
            TrailerCard(trailer)
        }
        if (extras.studios.isNotEmpty()) {
            DetailSectionTitle(stringResource(R.string.studios))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                extras.studios.forEach { studio ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = studio,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
        if (extras.characters.isNotEmpty()) {
            DetailSectionTitle(stringResource(R.string.characters))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(extras.characters, key = AnimeCharacter::name) { character ->
                    PersonCard(
                        name = character.name,
                        image = character.image,
                        role = localizedCharacterRole(character.role),
                        supportingText = character.voiceActor?.let { voiceActor ->
                            stringResource(R.string.voiced_by, voiceActor)
                        }
                    )
                }
            }
        }
        if (extras.staff.isNotEmpty()) {
            DetailSectionTitle(stringResource(R.string.staff))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(extras.staff, key = AnimeStaffMember::name) { staff ->
                    PersonCard(
                        name = staff.name,
                        image = staff.image,
                        role = staff.role,
                        supportingText = null
                    )
                }
            }
        }
        if (extras.recommendations.isNotEmpty()) {
            DetailSectionTitle(stringResource(R.string.similar_anime))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(extras.recommendations, key = Anime::id) { anime ->
                    HomeAnimeCard(anime, onAnime)
                }
            }
        }
    }
}

@Composable
private fun TrailerCard(trailer: AnimeTrailer) {
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { uriHandler.openUri(trailer.url) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        trailer.thumbnail?.let { thumbnail ->
            AsyncImage(
                model = thumbnail,
                contentDescription = stringResource(R.string.trailer),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
        }
        Text(
            text = stringResource(R.string.play_trailer),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PersonCard(
    name: String,
    image: String?,
    role: String?,
    supportingText: String?
) {
    Card(
        modifier = Modifier.width(140.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        AsyncImage(
            model = image,
            contentDescription = name,
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.padding(8.dp)) {
            Text(
                text = name,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            role?.takeIf(String::isNotBlank)?.let { label ->
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            supportingText?.let { label ->
                Text(
                    text = label,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun localizedCharacterRole(role: String?): String? {
    return when (role) {
        "MAIN" -> stringResource(R.string.character_role_main)
        "SUPPORTING" -> stringResource(R.string.character_role_supporting)
        "BACKGROUND" -> stringResource(R.string.character_role_background)
        else -> metadataFallbackLabel(role)
    }
}
