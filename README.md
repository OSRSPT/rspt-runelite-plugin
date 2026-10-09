# RSPT

RuneLite plugin for the RSPT (RuneScape Portugal) clan. When you receive a drop that counts for an
RSPT event (bingo, circles, daily item) on [event.rspt.pt](https://event.rspt.pt), the plugin reports
it so the event tile is completed automatically, or queued for staff review.

## Setup

1. Log in at https://event.rspt.pt/me and generate your plugin token.
2. In RuneLite, open the **RSPT** plugin settings and paste it into **Token do plugin**.

The plugin does nothing until a token is set.

## What is sent, and where

All requests go to `https://event.rspt.pt` (fixed, not configurable) and carry your token in the
`X-RSPT-Token` header. Nothing is sent when no token is set, on non-standard worlds (Leagues,
Deadman, beta, etc.), or before your character has fully logged in.

| When | Request | Data |
|---|---|---|
| On login, every few minutes while logged in (server-chosen, 1 to 60 min, default 5), and when the token changes | `GET /api/plugin/watchlist` | Only the token. The response is the list of item ids that currently count for your events. |
| When the Loot Tracker reports loot containing at least one watched item | `POST /api/plugin/drop` (multipart) | `payload`: a random drop id, your RSN, your account hash, the game mode (`STANDARD`), the loot source name and kill count from the Loot Tracker, and the watched item ids and quantities only (other items in the same loot are not sent). `screenshot`: a JPEG of the game client (max 1600 px on the longest edge), used by RSPT staff as proof. |

Only your own character's data is sent. Your IP address is visible to the event.rspt.pt server, as
with any web request. Loot that contains no watched item is never sent.

The server's answer is shown as a chat message and/or the game's notification popup (both can be
turned off in the settings).

## Português

Plugin do clã RSPT. Quando recebes um drop que conta para um evento RSPT em event.rspt.pt, o
plugin envia-o e o item fica automaticamente concluído no tile (ou fica a aguardar validação do
staff).

Configuração: gera o token em https://event.rspt.pt/me e cola-o em **Token do plugin** nas
definições do plugin RSPT.

O que é enviado: só quando recebes um item que está num evento ativo, o plugin envia para
event.rspt.pt o teu RSN, o hash da conta, a origem do drop, os itens desse evento e uma captura de
ecrã do jogo. Sem token, nada é enviado.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
