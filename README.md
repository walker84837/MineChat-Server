# MineChat Server

[![Ci status](https://github.com/walker84837/MineChat-Server/actions/workflows/build.yml/badge.svg)](https://github.com/walker84837/MineChat-Server/actions/workflows/build.yml)

This plugin implements the server-side component of the MineChat platform. It lets you chat on a Minecraft server without having to log in to a Minecraft account.

The plugin works by generating temporary codes that players can use to authenticate from the [client](https://github.com/walker84837/minechat-client). This helps bridge in-game chat with external MineChat clients.

## Features

- **Client authentication**: MineChat clients authenticate by using the generated link code or by reusing their stored client UUID.
- **Chat broadcast**: In-game chat is broadcast to all connected MineChat clients and vice versa.
- **Persistent Storage**: The plugin stores link codes and client information on disk, so that data remains between server restarts.
- **Automatic cleanup**: Expired link codes are cleaned up automatically every minute.

## Installation

1. **Requirements**:
   - A server running [Paper](https://papermc.io/) or any of its forks.
   - [CommandAPI](https://commandapi.jorel.dev/) to register the commands.

2. **Download and install the plugin**:
   - Download the latest release from the [releases](https://github.com/walker84837/MineChat-Server/releases/latest) page.
   - Place the downloaded JAR file into your server's `plugins` directory.

3. **Start Your Server**: Start or restart your Paper server to load the MineChat Server Plugin.

## Usage

### In-game: linking your account

1. **Generate a link code**:
   - In-game, run the `/link` command.
   - You will receive a temporary link code in chat. This code is valid for 5 minutes.

2. **Link from the [client](https://github.com/walker84837/minechat-client)**:
   - Use the provided code in your MineChat CLI client to authenticate:
     ```bash
     minechat-client --server <host:port> --link <code>
     ```
   - This links your MineChat client to your Minecraft account without needing to log in with your Minecraft credentials.

## How it works

- **Initial phase**:  
  The plugin opens a server socket on port `25575` to listen for connections from MineChat clients.

- **Authentication**:
  - Clients use either a new link code or their stored client UUID to authenticate.
  - Successful authentication triggers in-game notifications (join/leave messages) to all players.
  
- **Message broadcasting**:
  - The plugin listens for in-game chat events and broadcasts messages to connected clients.
  - Similarly, messages received from clients are broadcast to the Minecraft chat.

- **Persistent storage**:
  - Link codes and client information are stored in JSON files under `plugins/MineChat/` (e.g., `link_codes.json` and `clients.json`).

## Contributing

I'm relatively new to making plugins, so I won't exclude the chance I'm not following conventions in some parts or missing some things.

Contributions are welcome! Feel free to open [issues](https://github.com/walker84837/MineChat-Server/issues) or submit pull requests.

### Roadmap

- [ ] Allow for messages sent from MineChat clients to be visible to plugins like Discord bridges, such as [DiscordSRV](https://github.com/DiscordSRV/DiscordSRV).
- [ ] Consider switching to a more efficient format for persistent storage
- [ ] Allow for configuration, such as port, etc

## License

This project is licensed under the MPL-2.0 license. See the [LICENSE](LICENSE) file for details.
