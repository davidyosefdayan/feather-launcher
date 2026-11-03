# Contributing to Feather Launcher

Thanks for helping. Bug fixes, fixes for a particular phone, and small improvements are all welcome.

## Before your first pull request

**Sign the Contributor License Agreement.** On your first pull request, the CLA Assistant bot comments with a link: sign in with GitHub and agree once. That covers your present and future contributions. The [CLA](CLA.md) explains what it means: you keep the copyright to your work and give the maintainer permission to use it in Feather Launcher under any license. Feather Launcher stays under the GNU General Public License v3.0, and every version released under it stays available under it.

## Making a change

- **Open an issue first** for anything bigger than a bug fix, so we can agree on the approach before you spend time on it.
- **Keep it focused:** one change per pull request, in the style of the code around it.
- **Test on a phone or emulator,** ideally a small screen and Android 11 as well (Feather supports Android 11 and newer).
- **Describe what changed and how you tested it** in the pull request.

## Trying your build

Debug builds install as **Feather Dev**, a separate app next to Feather, so testing never touches your real home screen:

```sh
./gradlew installDebug
```

Every pull request also gets a Feather Dev build from GitHub; a comment on the pull request links to it. Any Feather Dev build installs over any other and keeps its settings.
