package trader.service.tradlet;

import trader.common.util.DateUtil;
import trader.service.trade.Order;
import trader.service.trade.TradeConstants.OrderAction;
import trader.service.tradlet.TradletConstants.PlaybookState;

/**
 * Playbook元组信息实现类
 */
public class PlaybookStateTupleImpl implements PlaybookStateTuple {

    private PlaybookState state;
    private long timestamp;
    private Order order;
    private OrderAction orderAction;
    private String actionId;

    PlaybookStateTupleImpl(PlaybookState state, Order order, OrderAction orderAction, String tradletActionId){
        this.state = state;
        this.order = order;
        this.orderAction = orderAction;
        this.actionId = tradletActionId;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public PlaybookState getState() {
        return state;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public Order getOrder() {
        return order;
    }

    @Override
    public OrderAction getOrderAction() {
        return orderAction;
    }

    public String getActionId() {
        return actionId;
    }

    @Override
    public String toString() {
        return "["+state+", order ref: "+order.getRef()+" action "+orderAction+" id "+actionId+" at "+DateUtil.long2datetime(timestamp)+"]";
    }

}
