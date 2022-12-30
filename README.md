# Simple Server Roles

*A Fabric mod, which allows players to self-manage their roles (teams)*

## What is this?

A **Fabric** mod, which allows players to self-manage their roles (teams) in Minecraft. Players can now ultilize the `/roles` command to create, join, leave and edit roles. The name and color of the team is then being shown on the players TAB-list. Additionally, the players receive a small "welcome" message when they join the server and are part of a role with at least two players.

These are the commands:
```
/roles list
/roles create <name>
/roles join <name>
/roles leave
/roles edit <property> <value>
```

*Note that the implementation itself utilizes the servers teams. This implies that a player can't be part of two roles at the same times - this also includes teams!* Players won't be able to join existing teams, but they can create their own roles at will.