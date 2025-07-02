package org.weiwei.mythicDistribute.util;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorConversion {


    public static final Pattern HEX_PATTERN = Pattern.compile("&#(\\w{5}[0-9a-f])");


    // 16進制顏色代碼轉換
    public static String translateHexCodes(String textToTranslate) {
        Matcher matcher = HEX_PATTERN.matcher(textToTranslate);
        StringBuffer buffer = new StringBuffer();

        while(matcher.find()) {
            matcher.appendReplacement(buffer, ChatColor.of("#" + matcher.group(1)).toString());
        }
        return matcher.appendTail(buffer).toString().replace("&", "§");
    }

    // 16進制顏色代碼轉換
    public static List<String> translateHexCodes(List<String> textToTranslateList) {
        return textToTranslateList.stream()
                .map(ColorConversion::translateHexCodes)
                .toList();
    }

    public static int getInt(String stToInt){
        try {
            return stToInt == null ? -1 : Integer.parseInt(stToInt);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static Player getPlayer(String playerSt){
        return Bukkit.getPlayer(playerSt);
    }


}
