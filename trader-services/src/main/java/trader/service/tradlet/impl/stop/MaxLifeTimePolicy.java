package trader.service.tradlet.impl.stop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import trader.common.beans.BeansContainer;
import trader.common.util.JsonEnabled;
import trader.service.md.MarketData;
import trader.service.tradlet.Playbook;
import trader.service.tradlet.PlaybookStateTuple;

/**
 * 最长生存周期止损策略
 */
public class MaxLifeTimePolicy extends AbsStopPolicy implements JsonEnabled {

    private long maxLifeTime;

    MaxLifeTimePolicy(BeansContainer beansContainer, Playbook playbook) {
        super(beansContainer);
        maxLifeTime = PBATTR_MAX_LIFETIME.getLong(playbook.getAttr(PBATTR_MAX_LIFETIME.name()));
    }

    @Override
    public String needStop(Playbook playbook, MarketData tick) {
        String result = null;
        PlaybookStateTuple openedState = null;
        if ( maxLifeTime!=0 && (openedState=playbook.getStateTuple(PlaybookState.Opened))!=null ) {
            long readyTime = openedState.getTimestamp();
            long currTime = mtService.currentTimeMillis();
            if ( marketTimeGreateThan(playbook.getInstrument(), readyTime, currTime, maxLifeTime) ){
                result = PBACTION_MAXLIFETIME+" "+maxLifeTime/1000+"s";
            }
        }
        return result;
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("maxLifeTime", maxLifeTime);
        return json;
    }

}