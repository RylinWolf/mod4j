package com.wolfhouse.mod4j.utils;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机数工具
 *
 * @author Rylin Wolf
 */
public class RandomUtils {
    /**
     * 获取正态分布随机数
     *
     * @param mean 均值
     * @param std  标准差
     * @return 随机数
     */
    public static double getGaussian(double mean, double std) {
        return mean + new Random().nextGaussian() * std;
    }

    /**
     * 获取布尔值，指定 true 的概率
     *
     * @param probability true 概率，取值范围为[0, 1]
     * @return true 或 false
     */
    public static boolean boolWithProbability(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }
}
