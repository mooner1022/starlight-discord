---
title: Discord setup
summary: Connecting the Discord bot: the token, gateway intents, the settings that decide which messages reach scripts, and using JDA from a script.
---

The Discord plugin logs a bot into Discord with [JDA](https://jda.wiki) and passes the messages it receives to the
projects.

## The bot token

- Create a bot in the Discord Developer Portal and copy its token from **Bot › Token**.
- StarLight asks for the token in the plugin's initial setup when the plugin starts without one, and again when Discord
  rejects the saved token. The setup is also in the plugin list and the plugin's info screen.
- The client starts once Discord accepts the token. **제어** in the plugin's settings shows its state, starts, stops
  and restarts it, and opens the invite link that adds the bot to a server.

## Gateway intents

The client always enables JDA's default intents. **인텐트 선택** adds more; `MESSAGE_CONTENT` is on by default, and
without it messages from servers arrive with empty text. Intents marked ★ (`MESSAGE_CONTENT`, `GUILD_MEMBERS`,
`GUILD_PRESENCES`) must also be turned on in the Developer Portal, under **Bot › Privileged Gateway Intents**. A
changed intent takes effect when the client restarts.

## Which messages reach scripts

- **onMessageCreate** (`discord.message.create`) gets every message the bot receives, as JDA's `MessageReceivedEvent`.
- **본인 메세지 무시** and **봇 메세지 무시**, on by default, leave out the bot's own messages and other bots'.
- **onMessage 이벤트 사용** also passes each message to the `onMessage` function, as StarLight's message, so scripts
  written for messengers handle Discord too. `event.packageName` is `"discord"`, and `event.room.send` sends to the
  channel.
- **레거시(response) 이벤트 사용**, shown when the above is on, also calls the legacy `response` function.

## JDA from a script

`Discord.getApi()` returns the bot's `JDA` object, for anything the events don't cover, such as
`Discord.getApi().getGuilds()`. It throws while the client isn't running. JDA's documentation is at
https://docs.jda.wiki; avoid its blocking calls, such as `complete()`, inside JDA's own event listeners.
