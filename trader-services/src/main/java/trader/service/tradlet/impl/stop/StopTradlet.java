package trader.service.tradlet.impl.stop;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import trader.common.beans.BeansContainer;
import trader.common.beans.Discoverable;
import trader.common.exception.AppThrowable;
import trader.common.util.JsonEnabled;
import trader.common.util.JsonUtil;
import trader.common.util.StringUtil;
import trader.service.ServiceErrorConstants;
import trader.service.md.MarketData;
import trader.service.md.MarketDataService;
import trader.service.ta.LeveledTimeSeries;
import trader.service.trade.MarketTimeService;
import trader.service.tradlet.Playbook;
import trader.service.tradlet.PlaybookCloseReq;
import trader.service.tradlet.PlaybookKeeper;
import trader.service.tradlet.PlaybookStateTuple;
import trader.service.tradlet.Tradlet;
import trader.service.tradlet.TradletConstants;
import trader.service.tradlet.TradletContext;
import trader.service.tradlet.TradletGroup;

/**
 * 简单止盈止损策略, 用于开仓后一段时间内止损, 需要Playbook属性中明确止损幅度.
 * <BR>目前使用止损方式
 * <LI>价格阶梯止损: 在某个价格之上保持一段时间即止损.
 * <LI>最长持仓时间: 到达最大持仓时间后, 即平仓
 * <LI>最后持仓时间: 到达某绝对市场时间, 即平仓
 *
 * 需要为每个playbook实例构建运行时数据, 设置playbook stopLoss.settings
 */
@Discoverable(interfaceClass = Tradlet.class, purpose = "STOP")
public class StopTradlet implements Tradlet, TradletConstants {
    private final static Logger logger = LoggerFactory.getLogger(StopTradlet.class);

    public static class PriceTrend implements JsonEnabled{

        @Override
        public JsonElement toJson() {
            JsonObject json = new JsonObject();

            return json;
        }
    }

    public static class EndTime implements JsonEnabled{

        @Override
        public JsonElement toJson() {
            JsonObject json = new JsonObject();
            return json;
        }
    }

    private BeansContainer beansContainer;
    private MarketDataService mdService;
    private MarketTimeService mtService;
    private TradletGroup group;
    private PlaybookKeeper playbookKeeper;
    private JsonObject templates;

    @Override
    public void init(TradletContext context) throws Exception
    {
        beansContainer = context.getBeansContainer();
        group = context.getGroup();
        playbookKeeper = context.getGroup().getPlaybookKeeper();
        mdService = beansContainer.getBean(MarketDataService.class);
        mtService = beansContainer.getBean(MarketTimeService.class);
        reload(context);
    }

    @Override
    public void reload(TradletContext context) throws Exception {
        if ( !StringUtil.isEmpty(context.getConfigText())) {
            templates = (JsonObject)(new JsonParser()).parse(context.getConfigText());
        }
    }

    @Override
    public void destroy() {

    }

    public String queryData(String queryExpr) {
        return null;
    }

    @Override
    public void onPlaybookStateChanged(Playbook playbook, PlaybookStateTuple oldStateTuple) {
        if ( oldStateTuple==null ) {
            //从Playbook 属性构建运行时数据.
            playbook.setAttr(PBATTR_STOP_RUNTIME, buildRuntime(playbook));
        }
    }

    @Override
    public void onTick(MarketData marketData) {
        checkActivePlaybooks(marketData);
    }

    @Override
    public void onNewBar(LeveledTimeSeries series) {
    }

    @Override
    public void onNoopSecond() {
        checkActivePlaybooks(null);
    }

    private void checkActivePlaybooks(MarketData tick) {
        if ( tick==null ) {
            return;
        }
        for(Playbook playbook:playbookKeeper.getActivePlaybooks(null)) {
            if ( !playbook.getExchangable().equals(tick.instrumentId)) {
                continue;
            }
            String closeReason = needStop(playbook, tick);
            if ( closeReason!=null ) {
                PlaybookCloseReq closeReq = new PlaybookCloseReq();
                closeReq.setActionId(closeReason);
                playbookKeeper.closePlaybook(playbook, closeReq);
            }
        }
    }

    /**
     * 检查是否需要立刻止损
     */
    private String needStop(Playbook playbook, MarketData tick) {
        AbsStopPolicy[] runtime = (AbsStopPolicy[])playbook.getAttr(PBATTR_STOP_RUNTIME);
        if ( runtime==null ) {
            return null;
        }
        for(int i=0;i<runtime.length;i++) {
            if ( runtime[i]!=null ) {
                String closeAction = runtime[i].needStop(playbook, tick);
                if ( closeAction!=null) {
                    return closeAction;
                }
            }
        }
        return null;
    }

    private AbsStopPolicy[] buildRuntime(Playbook playbook)
    {
        Object settingsObj = playbook.getAttr(PBATTR_STOP_SETTINGS);
        AbsStopPolicy[] result = null;
        Map<String, Object> settings = null;
        if ( settingsObj!=null ) {
            if ( settingsObj instanceof JsonElement ) {
                settings = (Map)JsonUtil.json2value((JsonElement)settingsObj);
            } else if ( settingsObj instanceof Map) {
                settings = (Map)settingsObj;
            } else {
                try{
                    settings = (Map)JsonUtil.json2value(JsonUtil.object2json(settingsObj));
                }catch(Throwable t) {
                    String str = AppThrowable.error2msg(ServiceErrorConstants.ERR_TRADLET_STOP_SETTINGS_INVALID, "Parse settings failed: {0}", t.toString());;
                    logger.error(str, t);
                }
            }
        }
        if ( settings!=null ) {
            long openingPrice = playbook.getMoney(PBMny.Opening);
            if ( openingPrice==0 ) {
                openingPrice = mdService.getLastData(playbook.getExchangable()).lastPrice;
            }
            result = new AbsStopPolicy[StopPolicy.values().length];
            //SimpleStop
            String key = StopPolicy.SimpleLoss.name();
            if ( settings.containsKey(key)) {
                result[StopPolicy.SimpleLoss.ordinal()] = new SimpleLossPolicy(beansContainer, playbook, openingPrice, settings.get(key));
            }
            //PriceStepGain
            key = StopPolicy.PriceStepGain.name();
            if ( settings.containsKey(key)) {
                result[StopPolicy.PriceStepGain.ordinal()] = new PriceStepGainPolicy(beansContainer, playbook, openingPrice, (List)settings.get(key));
            }
            //PriceTrend
            key = StopPolicy.PriceTrendLoss.name();
            if ( settings.containsKey(key)) {
                result[StopPolicy.PriceTrendLoss.ordinal()] = new PriceTrendLossPolicy(beansContainer, playbook, openingPrice, settings.get(key));
            }
            //MaxLifeTime
            key = StopPolicy.MaxLifeTime.name();
            if ( settings.containsKey(key)) {
                result[StopPolicy.MaxLifeTime.ordinal()] = new MaxLifeTimePolicy(beansContainer, settings.get(key));
            }
            //EndTime
            key = StopPolicy.EndTime.name();
            if ( settings.containsKey(key)) {
                result[StopPolicy.EndTime.ordinal()] = new EndTimePolicy(beansContainer, playbook, settings.get(key));
            }
        }
        return result;
    }

}
