# Privacy policy: Drive Mode for Claude Code (Claude Auto)

Last updated: 27 October 2026

Drive Mode for Claude Code ("the app", package `com.claudeauto`) lets you talk to Claude Code from your phone and from Android Auto. It is an independent, open-source app, not made by Anthropic. This policy explains what the app does with your data. The short version: there is no account with us and no server of ours, and we never receive your data.

## What the app handles, and where it goes

**Your messages and Claude's replies.** What you type or dictate is sent to the Claude Code backend you choose during setup, and only there:

- **My computer / Raspberry Pi:** the open-source Claude Auto bridge running on your own computer, over an encrypted (HTTPS) connection pinned to that computer's certificate. Claude Code on that computer then talks to Anthropic using your own Claude login, under [Anthropic's privacy policy](https://www.anthropic.com/legal/privacy).
- **This phone:** the bridge running in Termux on the same phone, over the phone's local loopback connection, and from there to Anthropic as above.
- **Cloud:** Anthropic's API, over HTTPS, using the API key you enter. Anthropic handles that data under its own terms and privacy policy. In this mode, the app also gives the cloud sandbox your GitHub token so it can work on the repositories you list.

The app keeps your recent conversations on the phone so it can show them and read them aloud. They are not backed up to the cloud by the app (Android backup is turned off for it).

**Your voice.** When you tap the microphone, the app uses your phone's speech recognition service (usually Google's) to turn speech into text. That service may process audio under its own provider's privacy policy. The app itself does not record, store or send audio. Replies are read aloud by your phone's text-to-speech engine.

**Settings and keys.** Your bridge address, the pairing token the bridge issues, and in cloud mode your Anthropic API key and GitHub token, are stored only in the app's private storage on your phone. They are sent only to the service they belong to.

**Camera.** The QR code scanner used for pairing is provided by Google Play services. The app receives only the decoded pairing link, not camera images.

**Reports.** If you use "Report this response", your email app opens with the reported response and the reason you picked, addressed to the developer. Nothing is sent unless you send that email. We use reports only to look into the problem you reported.

## What we don't do

- No analytics, advertising, tracking or crash-reporting SDKs.
- No account, and no data collected on any server run by the developer.
- No selling or sharing of your data.

## Deleting your data

Uninstalling the app, or clearing its storage, deletes everything it stored on your phone. To remove a paired phone from your computer, run `claude-auto-bridge devices revoke <id>` on that computer (`claude-auto-bridge devices` lists them). Conversation history kept by Claude Code on your computer, or by Anthropic in cloud mode, is managed there.

## Children

The app is not directed at children under 13.

## Changes

Changes to this policy are published in this file, with the date above updated. The full history is in the repository.

## Contact

Open an issue at https://github.com/Psicrone/claude-for-andriod-auto/issues, or email the developer address shown on the app's Google Play listing.
