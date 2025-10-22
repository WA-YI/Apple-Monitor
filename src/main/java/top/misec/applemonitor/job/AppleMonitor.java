package top.misec.applemonitor.job;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;
import top.misec.applemonitor.config.*;
import top.misec.applemonitor.push.impl.FeiShuBotPush;
import top.misec.applemonitor.push.pojo.feishu.FeiShuPushDTO;
import top.misec.bark.BarkPush;
import top.misec.bark.enums.SoundEnum;
import top.misec.bark.pojo.PushDetails;

import java.util.*;

/**
 * Apple Monitor 主任务
 * 支持使用 Playwright 模拟浏览器发起请求，避免频率限制
 *
 * @author
 */
@Slf4j
public class AppleMonitor {

    private final AppCfg CONFIG = CfgSingleton.getInstance().config;

    /**
     * 是否启用 Playwright
     * 启用后将使用 Chromium 模拟浏览器发起请求
     */
    private final boolean USE_PLAYWRIGHT = true;

    public void monitor() {
        List<DeviceItem> deviceItemList = CONFIG.getAppleTaskConfig().getDeviceCodeList();

        try {
            for (DeviceItem deviceItem : deviceItemList) {
                doMonitor(deviceItem);
                Thread.sleep(1500);
            }
        } catch (Exception e) {
            log.error("AppleMonitor Error", e);
        }
    }

    public void pushAll(String content, List<PushConfig> pushConfigs) {
        pushConfigs.forEach(push -> {

            if (StrUtil.isAllNotEmpty(push.getBarkPushUrl(), push.getBarkPushToken())) {
                BarkPush barkPush = new BarkPush(push.getBarkPushUrl(), push.getBarkPushToken());
                PushDetails pushDetails = PushDetails.builder()
                        .title("苹果商店监控")
                        .body(content)
                        .category("苹果商店监控")
                        .group("Apple Monitor")
                        .sound(StrUtil.isEmpty(push.getBarkPushSound()) ?
                                SoundEnum.GLASS.getSoundName() : push.getBarkPushSound())
                        .build();
                barkPush.simpleWithResp(pushDetails);
            }

            if (StrUtil.isAllNotEmpty(push.getFeishuBotSecret(), push.getFeishuBotWebhooks())) {
                FeiShuBotPush.pushTextMessage(FeiShuPushDTO.builder()
                        .text(content)
                        .secret(push.getFeishuBotSecret())
                        .botWebHooks(push.getFeishuBotWebhooks())
                        .build());
            }
        });
    }

    public void doMonitor(DeviceItem deviceItem) {

        Map<String, Object> queryMap = new HashMap<>(5);
        queryMap.put("pl", "true");
        queryMap.put("mts.0", "regular");
        queryMap.put("parts.0", deviceItem.getDeviceCode());
        queryMap.put("location", CONFIG.getAppleTaskConfig().getLocation());

        String baseCountryUrl = CountryEnum.getUrlByCountry(CONFIG.getAppleTaskConfig().getCountry());
        Map<String, List<String>> headers = buildHeaders(baseCountryUrl, deviceItem.getDeviceCode());

        String url = baseCountryUrl + "/shop/fulfillment-messages?" +
                URLUtil.buildQuery(queryMap, CharsetUtil.CHARSET_UTF_8);

        try {
            JSONObject responseJsonObject;

            if (USE_PLAYWRIGHT) {
                // ✅ 使用 Playwright 发起请求
                try (Playwright playwright = Playwright.create()) {
                    Browser browser = playwright.chromium().launch(
                            new BrowserType.LaunchOptions().setHeadless(true)
                    );

                    BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                            .setExtraHTTPHeaders(Map.of(
                                    "Referer", baseCountryUrl + "/shop/buy-iphone/iphone-14-pro/" + deviceItem.getDeviceCode(),
                                    "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleMonitor/1.0"
                            ))
                    );

                    Page page = context.newPage();
                    page.navigate(url, new Page.NavigateOptions().setTimeout(30000));

                    // 获取返回的 JSON 文本
                    String body = page.evaluate("() => document.body.innerText");
                    responseJsonObject = JSONObject.parseObject(body);

                    browser.close();
                }
            } else {
                // ✅ 使用原始 HttpRequest
                try (HttpResponse httpResponse = HttpRequest.get(url).header(headers).execute()) {
                    if (!httpResponse.isOk()) {
                        log.info("请求过于频繁，请调整 cronExpressions 或启用 Playwright 模式");
                        return;
                    }
                    responseJsonObject = JSONObject.parseObject(httpResponse.body());
                }
            }

            JSONObject pickupMessage = responseJsonObject.getJSONObject("body")
                    .getJSONObject("content")
                    .getJSONObject("pickupMessage");

            JSONArray stores = pickupMessage.getJSONArray("stores");

            if (stores == null) {
                log.info("您可能填错产品代码，目前仅支持中国和日本地区的产品");
                log.debug(pickupMessage.toString());
                return;
            }

            if (stores.isEmpty()) {
                log.info("您所在的 {} 附近没有 Apple 直营店，请检查地址是否正确",
                        CONFIG.getAppleTaskConfig().getLocation());
            }

            stores.stream()
                    .filter(store -> deviceItem.getStoreWhiteList().isEmpty()
                            || filterStore((JSONObject) store, deviceItem))
                    .forEach(k -> {
                        JSONObject storeJson = (JSONObject) k;
                        JSONObject partsAvailability = storeJson.getJSONObject("partsAvailability");

                        String storeNames = storeJson.getString("storeName").trim();
                        String deviceName = partsAvailability.getJSONObject(deviceItem.getDeviceCode())
                                .getJSONObject("messageTypes").getJSONObject("regular")
                                .getString("storePickupProductTitle");
                        String productStatus = partsAvailability.getJSONObject(deviceItem.getDeviceCode())
                                .getString("pickupSearchQuote");

                        String content = StrUtil.format("门店:{}, 型号:{}, 状态:{}", storeNames, deviceName, productStatus);

                        if (judgingStoreInventory(storeJson, deviceItem.getDeviceCode())) {
                            JSONObject retailStore = storeJson.getJSONObject("retailStore");
                            content += buildPickupInformation(retailStore);
                            log.info(content);
                            pushAll(content, deviceItem.getPushConfigs());
                        }
                        log.info(content);
                    });

        } catch (Exception e) {
            log.error("AppleMonitor error", e);
        }
    }

    private boolean judgingStoreInventory(JSONObject storeJson, String productCode) {
        JSONObject partsAvailability = storeJson.getJSONObject("partsAvailability");
        String status = partsAvailability.getJSONObject(productCode).getString("pickupDisplay");
        return "available".equals(status);
    }

    private String buildPickupInformation(JSONObject retailStore) {
        String distanceWithUnit = retailStore.getString("distanceWithUnit");
        String twoLineAddress = retailStore.getJSONObject("address").getString("twoLineAddress");
        if (StrUtil.isEmpty(twoLineAddress)) {
            twoLineAddress = "暂无取货地址";
        }

        String daytimePhone = retailStore.getJSONObject("address").getString("daytimePhone");
        if (StrUtil.isEmpty(daytimePhone)) {
            daytimePhone = "暂无联系电话";
        }

        String lo = CONFIG.getAppleTaskConfig().getLocation();
        String messageTemplate = "\n取货地址:{}, 电话:{}, 距离{}:{}";
        return StrUtil.format(messageTemplate, twoLineAddress.replace("\n", " "), daytimePhone, lo, distanceWithUnit);
    }

    private boolean filterStore(JSONObject storeInfo, DeviceItem deviceItem) {
        String storeName = storeInfo.getString("storeName");
        return deviceItem.getStoreWhiteList().stream()
                .anyMatch(k -> storeName.contains(k) || k.contains(storeName));
    }

    private Map<String, List<String>> buildHeaders(String baseCountryUrl, String productCode) {
        ArrayList<String> referer = new ArrayList<>();
        referer.add(baseCountryUrl + "/shop/buy-iphone/iphone-14-pro/" + productCode);

        Map<String, List<String>> headers = new HashMap<>(10);
        headers.put(Header.REFERER.getValue(), referer);
        return headers;
    }
}
