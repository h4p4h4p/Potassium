package com.potassium.command;

import com.potassium.util.Friends;

import java.util.List;

public class FriendCommand extends ChatCommand {

    public FriendCommand() {
        super("friend", "friends", "f");
    }

    @Override
    public String getUsage() {
        return "%friend add|remove <name> | %friend list | %friend clear";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            CommandManager.sendMessage("\u00a7cUsage: " + getUsage());
            return;
        }

        String action = args[0].toLowerCase();
        if (action.equals("list")) {
            List<String> names = Friends.getNames();
            if (names.isEmpty()) {
                CommandManager.sendMessage("\u00a77Friend list is empty.");
                return;
            }
            CommandManager.sendMessage("\u00a7eFriends \u00a77(" + names.size() + "): \u00a7a"
                    + String.join("\u00a77, \u00a7a", names));
            return;
        }

        if (action.equals("clear")) {
            Friends.clear();
            CommandManager.sendMessage("\u00a7aFriend list cleared.");
            return;
        }

        if (!action.equals("add") && !action.equals("remove")) {
            CommandManager.sendMessage("\u00a7cUsage: " + getUsage());
            return;
        }
        if (args.length != 2) {
            CommandManager.sendMessage("\u00a7cUsage: " + getUsage());
            return;
        }

        String name = args[1];
        if (!Friends.isValidName(name)) {
            CommandManager.sendMessage("\u00a7cThat is not a valid Minecraft name.");
            return;
        }

        if (action.equals("add")) {
            if (Friends.add(name)) {
                CommandManager.sendMessage("\u00a7aAdded \u00a7f" + name + " \u00a7ato the friend list.");
            } else {
                CommandManager.sendMessage("\u00a7f" + name + " \u00a77is already a friend.");
            }
            return;
        }

        if (Friends.remove(name)) {
            CommandManager.sendMessage("\u00a7aRemoved \u00a7f" + name + " \u00a7afrom the friend list.");
        } else {
            CommandManager.sendMessage("\u00a7f" + name + " \u00a77is not on the friend list.");
        }
    }
}
