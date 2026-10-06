# Pearl Throw

A Minecraft Fabric mod for version 26.2 that automates ender pearl trajectory prediction with wind charge interception.

## Features

- **Middle Mouse Button**: Throws an ender pearl where you're aiming
- **Automatic Prediction**: Calculates the exact trajectory of the thrown pearl
- **Wind Charge Intercept**: Automatically fires a wind charge timed to explode exactly where the pearl will be
- **Inheritance Physics**: Accounts for your movement velocity when calculating trajectories
- **Safety Checks**: Ensures minimum safe distance from the explosion
- **Error Tolerance**: Displays predicted miss distance in blocks

## Keybindings

- **Middle Mouse (Default)**: Trigger pearl throw + wind charge intercept

Can be rebound in Minecraft controls menu under "PearlThrow" category.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.16.5+
- Fabric API 0.152.2+26.2+
- Java 21+

## Building

```bash
./gradlew build
```

The compiled mod will be in `build/libs/`.

## Installation

1. Place the compiled JAR in your `mods` folder
2. Start Minecraft with Fabric Loader
3. Enjoy automated pearl throwing!

## How It Works

1. Press Middle Mouse to start
2. An ender pearl is thrown from your current position at your current aim
3. The mod simulates the pearl's flight including gravity and air drag
4. It calculates where a wind charge needs to be aimed to intercept the pearl
5. After a delay (1-4 ticks), the wind charge is fired at the calculated intercept point
6. Both projectiles meet at the same location and time

## Technical Details

- Pearl physics: 1.5 launch speed, 0.03 gravity, 0.99 drag
- Wind charge: No gravity, constant speed (1.5), straight line
- Maximum prediction: 80 ticks ahead
- Maximum delay between pearl and charge: 4 ticks
- Hit tolerance: 0.35 blocks
- Minimum safe distance: 4 blocks

## License

MIT License - See LICENSE file for details
