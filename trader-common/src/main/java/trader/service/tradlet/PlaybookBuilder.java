package trader.service.tradlet;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import trader.common.exchangeable.Exchangeable;
import trader.service.trade.TradeConstants.OrderPriceType;
import trader.service.trade.TradeConstants.PosDirection;

/**
 * 创建一个新的交易剧本
 */
public class PlaybookBuilder {

    private Exchangeable instrument;
    private int volume = 1;
    private PosDirection openDirection;
    private long openPrice;
    private OrderPriceType priceType = OrderPriceType.Unknown;
    private String openTimeout;
    private Map<String, Object> attrs = new HashMap<>();
    private String openActionId;

    public Exchangeable getInstrument() {
        return instrument;
    }

    public PlaybookBuilder setInstrument(Exchangeable i) {
        this.instrument = i;
        return this;
    }

    public String getOpenActionId() {
        return openActionId;
    }

    /**
     * 设置openActionId, 这个属性将会被设置到 Order.ODRATTR_PLAYBOOK_ACTION_ID 中
     */
    public PlaybookBuilder setOpenActionId(String openActionId) {
        this.openActionId = openActionId;
        return this;
    }

    public int getVolume() {
        return volume;
    }

    public PlaybookBuilder setVolume(int volume) {
        this.volume = volume;
        return this;
    }

    public PosDirection getOpenDirection() {
        return openDirection;
    }

    public PlaybookBuilder setOpenDirection(PosDirection dir) {
        this.openDirection = dir;
        return this;
    }

    public long getOpenPrice() {
        return openPrice;
    }

    public OrderPriceType getPriceType() {
        return priceType;
    }

    public String getOpenTimeout() {
        return openTimeout;
    }

    /**
     * 设置价格类型, 缺省为Unknown, 意味着自动设置为最近价格的LimitPrice
     */
    public PlaybookBuilder setPriceType(OrderPriceType priceType) {
        this.priceType = priceType;
        return this;
    }

    public PlaybookBuilder setOpenPrice(long openPrice) {
        this.openPrice = openPrice;
        return this;
    }

    public PlaybookBuilder setOpenTimeout(String openTimeout) {
        this.openTimeout = openTimeout;
        return this;
    }

    public Map<String, Object> getAttrs() {
        return attrs;
    }

    /**
     * 设置Playbook 属性
     */
    public PlaybookBuilder setAttr(String key, Object value) {
        if ( value==null ) {
            attrs.remove(key);
        }else {
            attrs.put(key, value);
        }
        return this;
    }

    /**
     * 合并Playbook模板参数
     */
    public void mergeTemplateAttrs(Properties templateProps) {
        Map<String, Object> props = new HashMap<>();
        props.putAll((Map)templateProps);
        props.putAll(this.attrs);
        this.attrs = props;
    }

}
