package com.elasticcloudservice.predict;

import com.filetool.util.FileUtil;
import com.filetool.util.LogUtil;
import com.model.LinearRegression;
import com.model.Metrics;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class Predict {
    public static String[] predictVm(String[] ecsContent, String[] inputContent) {
        String[] results = new String[ecsContent.length];
        List<Record> history = new ArrayList<Record>();

        for (int i = 0; i < ecsContent.length; i++) {
            if (ecsContent[i].contains(" ") && ecsContent[i].split("\\t").length == 3) {
                String[] array = ecsContent[i].split("\\t");
                history.add(new Record(array[0], array[1], array[2]));
            }
        }

        // 物理服务器信息：CPU核数、内存大小（GB）、硬盘大小(不考虑)
        Server physical = new Server("pysical", Integer.valueOf(inputContent[0].split(" ")[0]), Integer.valueOf(inputContent[0].split(" ")[1]));

        int flavorNum = Integer.valueOf(inputContent[2]);

        Map<Server, Integer> predictFlavors = new HashMap<Server, Integer>();
        // 预测虚拟机的实际数量，用作计算评分
        Map<Server, Integer> flavorsNum = new HashMap<Server, Integer>();

        for (int i = 3; i < 3 + flavorNum; i++) {
            String[] array = inputContent[i].split(" ");
            Server flavor = new Server(array[0], Integer.valueOf(array[1]), Integer.valueOf(array[2]) / 1024);
            predictFlavors.put(flavor, 0);
            flavorsNum.put(flavor, 0);
        }

//        for (String line : testContent) {
//            for (Server server : flavorsNum.keySet()) {
//                if (line.split("\\t")[1].equals(server.getName())) {
//                    flavorsNum.put(server, flavorsNum.get(server) + 1);
//                }
//            }
//        }

        String[] actualFlavorNum = new String[flavorsNum.size()];
        int actualIndex = 0;
        for (Server flavor : flavorsNum.keySet()) {
            actualFlavorNum[actualIndex++] = flavor.getName() + " " + flavorsNum.get(flavor);
        }
//        FileUtil.write("/Users/cutoutsy/workspace/codecraft/data/example/case1/actual.txt", actualFlavorNum, false);

        // 优化资源名称，0：CPU, 1: MEM
        int optimization = inputContent[4 + flavorNum].equals("CPU") ? 0 : 1;

        // 预测开始时间和结束时间
        String start = inputContent[6 + flavorNum];
        String end = inputContent[7 + flavorNum];

        int period = (int)ChronoUnit.DAYS.between(LocalDate.parse(start.split(" ")[0]), LocalDate.parse(end.split(" ")[0]));
        System.out.println("period: " + period);
        // 生成预测训练数据
        Map<Server, List<List<Integer>>> trainData = Feature.createTrainData(history, predictFlavors, period, 5);
        for (Server server : trainData.keySet()) {
            List<List<Integer>> oneFlavorTrainData = trainData.get(server);
//            if (oneFlavorTrainData.size() < 10) {
//                predict(history, predictFlavors, start, end);
//                break;
//            }
            LinearRegression lr = new LinearRegression(oneFlavorTrainData, 0.001, 100000);
            lr.trainTheta();
            double[] theta = lr.getTheta();
            List<Integer> last = oneFlavorTrainData.get(oneFlavorTrainData.size() - 1);
            double num = 0;
            for (int i = 1; i < last.size(); i++) {
                num += theta[i-1] * last.get(i);
            }
            num = num <= 0 ? 0 : num;
            predictFlavors.put(server, (int)Math.round(num));
        }

//        LogUtil.printLog("predict score: " + Metrics.predictScore(flavorsNum, predictFlavors));

        // 预测结果若虚拟机总数为0
//        if (predictFlavors.values().stream().reduce(0, (acc, element) -> acc + element) == 0) {
//            predict(history, predictFlavors, start, end);
//        }

        results[0] = String.valueOf(predictFlavors.values().stream().reduce(0, (acc, element) -> acc + element));

        int resultIndex = 1;
        for (Server item : predictFlavors.keySet()) {
            results[resultIndex++] = item.getName() + " " + predictFlavors.get(item);
        }

        results[resultIndex++] = "";


        Map<Integer, Map<Server, Integer>> re = deploy(predictFlavors, physical, optimization);
        results[resultIndex++] = String.valueOf(re.size());

        for (int i = 0; i < re.size(); i++) {
            StringBuilder sb = new StringBuilder(i+1 + " ");
            for (Server server : re.get(i).keySet()) {
                sb.append(server.getName() + " " + re.get(i).get(server) + " ");
            }
            results[resultIndex++] = sb.toString().trim();
        }

        return results;
    }

    /**
     * 在物理服务器上进行虚拟机资源分配
     * 算法采用：首次适应算法 （后续可优化为降序最佳适应法）
     * 还要考虑单个维度资源问题：比如维度为CPU，那么在最佳适应法中选择满足情况中CPU最满的情况
     * @param predictFlavors
     * @param physical
     * @param optimization
     * @return
     */
    private static Map<Integer, Map<Server, Integer>> deploy(Map<Server, Integer> predictFlavors, Server physical, int optimization) {
        LogUtil.printLog("physical cpu:" + physical.getCpu());
        List<Server> servers = new ArrayList<Server>();
        Map<Integer, Map<Server, Integer>> result = new HashMap<>();
        int serverId = 1;
        servers.add(new Server(String.valueOf(serverId++), physical.getCpu(), physical.getMem()));
        for (Server flavor : predictFlavors.keySet()) {
            int num = predictFlavors.get(flavor);
            for (int i = 0; i < num; i++) {
                boolean flag = false;
                for (int j = 0; j < servers.size(); j++) {
                    Server temp = servers.get(j);
                    if (temp.getCpu() >= flavor.getCpu() && temp.getMem() >= flavor.getMem()) {
                        if (result.keySet().contains(j)) {
                            result.get(j).put(flavor, result.get(j).getOrDefault(flavor, 0) + 1);
                        } else {
                            HashMap<Server, Integer> add = new HashMap<>();
                            add.put(flavor, 1);
                            result.put(j, add);
                        }
                        temp.setCpu(temp.getCpu() - flavor.getCpu());
                        temp.setMem(temp.getMem() - flavor.getMem());
                        flag = true;
                        break;
                    }
                }
                if (!flag) {
                    servers.add(new Server(String.valueOf(serverId++), physical.getCpu(), physical.getMem()));
                    i--;
                }
            }
        }

        return result;
    }

    /**
     * 虚拟机数量预测
     * @param history
     * @param flavors
     * @param start
     * @param end
     */
    private static void predict(List<Record> history, Map<Server, Integer> flavors, String start, String end) {
        // 根据历史数据设置各个虚拟机规格的比率
        double[] flavorRatio = {0.076, 0.1, 0.017, 0.03, 0.126, 0.039, 0.0267, 0.219, 0.069, 0.014, 0.068, 0.059, 0.015, 0.0528, 0.025};
        // 历史申请总数
        int total = history.size();
        LogUtil.printLog("history total:" + total);
//        HashMap<String, Integer> flavor = new HashMap<String, Integer>();
        // 各种规格虚拟机的数量
//        for (Record record : history) {
//            flavor.put(record.getFlavorName(), flavor.getOrDefault(record.getFlavorName(), 0) + 1);
//        }
//        LogUtil.printLog("flavor2 num: " + flavor.get("flavor2"));
        long period = ChronoUnit.DAYS.between(LocalDate.parse(start.split(" ")[0]), LocalDate.parse(end.split(" ")[0]));
//        LocalDate.parse(start);

//        long totalTime = Timestamp.valueOf(history.get(history.size()-1).getCreateTime()).getTime() - Timestamp.valueOf(history.get(0).getCreateTime()).getTime();
        long totalTime = ChronoUnit.DAYS.between(LocalDate.parse(history.get(0).getCreateTime().split(" ")[0]), LocalDate.parse(history.get(history.size()-1).getCreateTime().split(" ")[0]));
        int periodNum = (int) (total / totalTime * period);
        LogUtil.printLog("period total:" + periodNum);
        for (Server server : flavors.keySet()) {
            String flavorName = server.getName();
            flavors.put(server, (int) (periodNum * flavorRatio[Integer.valueOf(flavorName.substring(6))-1]));
        }
    }

    public static void main(String[] args) {
//        String flavorName = "flavor1";
//        System.out.println(flavorName.substring(6));
        System.out.println(Math.round(12.43));
        System.out.println(Math.round(12.53));
    }
}