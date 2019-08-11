package trader.service.tradlet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import trader.common.exception.AppException;
import trader.common.exchangeable.Exchangeable;
import trader.common.util.DateUtil;
import trader.common.util.JsonEnabled;
import trader.common.util.JsonUtil;
import trader.common.util.PriceUtil;
import trader.common.util.StringUtil;
import trader.common.util.UUIDUtil;
import trader.service.ServiceErrorConstants;
import trader.service.md.MarketData;
import trader.service.md.MarketDataService;
import trader.service.trade.MarketTimeService;
import trader.service.trade.Order;
import trader.service.trade.OrderBuilder;
import trader.service.trade.TradeConstants;
import trader.service.trade.Transaction;

/**
 * 管理某个交易分组的报单和成交计划
 */
public class PlaybookKeeperImpl implements PlaybookKeeper, TradeConstants, TradletConstants, ServiceErrorConstants, JsonEnabled {
    private static final Logger logger = LoggerFactory.getLogger(PlaybookKeeperImpl.class);

    private TradletGroupImpl group;
    private MarketDataService mdService;
    private MarketTimeService mtService;
    private List<Order> allOrders = new ArrayList<>();
    private LinkedList<Order> pendingOrders = new LinkedList<>();
    private LinkedHashMap<String, PlaybookImpl> allPlaybooks = new LinkedHashMap<>();
    private LinkedList<PlaybookImpl> activePlaybooks = new LinkedList<>();

    public PlaybookKeeperImpl(TradletGroupImpl group) {
        this.group = group;
        mdService = group.getBeansContainer().getBean(MarketDataService.class);
        mtService = group.getBeansContainer().getBean(MarketTimeService.class);
    }

    @Override
    public List<Order> getAllOrders() {
        return allOrders;
    }

    @Override
    public List<Order> getPendingOrders() {
        return pendingOrders;
    }

    @Override
    public Order getLastOrder() {
        if ( allOrders.isEmpty() ) {
            return null;
        }
        return allOrders.get(allOrders.size()-1);
    }

    @Override
    public Order getLastPendingOrder() {
        if ( pendingOrders.isEmpty() ) {
            return null;
        }
        return pendingOrders.getLast();
    }

    @Override
    public void cancelAllPendingOrders() {
        for(Order order:pendingOrders) {
            if ( order.getStateTuple().getState().isRevocable() ) {
                try {
                    group.getAccount().cancelOrder(order.getRef());
                } catch (AppException e) {
                    logger.error("Tradlet group "+group.getId()+" cancel order "+order.getRef()+" failed "+e.toString(), e);
                }
            }
        }
    }

    @Override
    public List<Playbook> getAllPlaybooks() {
        return (List)allPlaybooks.values();
    }

    @Override
    public List<Playbook> getActivePlaybooks(String openActionIdExpr) {
        List<Playbook> result = null;
        if ( StringUtil.isEmpty(openActionIdExpr)) {
            result = (List)activePlaybooks;
        }else{
            throw new UnsupportedOperationException("query expr is not supported yet");
        }
        return result;
    }

    @Override
    public Playbook getPlaybook(String playbookId) {
        return allPlaybooks.get(playbookId);
    }

    @Override
    public Playbook createPlaybook(PlaybookBuilder builder) throws AppException
    {
        if ( group.getState()!=TradletGroupState.Enabled ) {
            throw new AppException(ERR_TRADLET_TRADLETGROUP_NOT_ENABLED, "Tradlet group "+group.getId()+" is not enabled");
        }
        String playbookId = "pbk_"+UUIDUtil.genUUID58();
        Exchangeable e = builder.getInstrument();
        if ( e==null ) {
            e = group.getInstruments().get(0);
        }
        OrderPriceType priceType = builder.getPriceType();
        long openPrice = builder.getOpenPrice();
        //自动使用对手价
        if ( priceType==OrderPriceType.Unknown ) {
            MarketData md = mdService.getLastData(e);
            if ( md!=null ) {
                if ( builder.getOpenDirection()==PosDirection.Long ) {
                    openPrice = md.lastAskPrice(); //开仓买多, 使用卖1价
                }else {
                    openPrice = md.lastBidPrice(); //开仓卖空, 使用买1价
                }
                priceType = OrderPriceType.LimitPrice;
            } else {
                priceType = OrderPriceType.BestPrice;
            }
        }
        OrderBuilder odrBuilder = new OrderBuilder();
        odrBuilder.setExchagneable(e)
            .setDirection(builder.getOpenDirection()==PosDirection.Long?OrderDirection.Buy:OrderDirection.Sell)
            .setLimitPrice(openPrice)
            .setPriceType(priceType)
            .setVolume(builder.getVolume())
            .setOffsetFlag(OrderOffsetFlag.OPEN)
            .setAttr(Order.ODRATTR_PLAYBOOK_ID, playbookId);
        //创建报单
        Order order = group.getAccount().createOrder(odrBuilder);

        PlaybookImpl playbook = new PlaybookImpl(group, playbookId, builder, new PlaybookStateTupleImpl(PlaybookState.Opening, order, OrderAction.Send, builder.getOpenActionId()));
        addOrder(order);
        allPlaybooks.put(playbookId, playbook);
        activePlaybooks.add(playbook);
        if ( logger.isInfoEnabled()) {
            logger.info("Tradlet group "+group.getId()+" create playbook "+playbookId+" with openning order "+order.getRef()+" action id "+builder.getOpenActionId());
        }
        group.onPlaybookStateChanged(playbook, playbook.getStateTuple());
        return playbook;
    }

    @Override
    public boolean closePlaybook(Playbook playbook0, PlaybookCloseReq closeReq) {
        boolean result = false;
        if ( playbook0!=null ) {
            PlaybookImpl playbook = (PlaybookImpl)playbook0;
            PlaybookStateTuple pbStateTuple = playbook.getStateTuple();
            PlaybookState pbState = pbStateTuple.getState();
            switch(pbState) {
            case Opening: //开仓过程中, 取消报单
                result = playbook.cancelOpeningOrder();
                break;
            case Opened: //已开仓, 平仓
                result = playbook.closeOpenedOrder(closeReq.getActionId());
                break;
            default:
                result = false;
                break;
            }
            if ( result ) {
                if ( closeReq.getTimeout()>0 ) {
                    playbook.setAttr(Playbook.PBATR_CLOSE_TIMEOUT, ""+closeReq.getTimeout());
                }
                if ( logger.isInfoEnabled()) {
                    logger.info("Tradlet group "+group.getId()+" close playbook "+playbook.getId()+" action id "+closeReq.getActionId()+" at "+DateUtil.date2str(mtService.getMarketTime()));
                }
            }
        }
        return result;
    }

    public void updateOnTxn(Transaction txn) {
        Order order = txn.getOrder();
        PlaybookImpl playbook = null;
        if ( order!=null ) {
            String playbookId = order.getAttr(Order.ODRATTR_PLAYBOOK_ID);
            playbook = allPlaybooks.get(playbookId);
        }
        if ( playbook!=null ) {
            playbook.updateOnTxn(txn);
        }
    }

    /**
     * 更新订单状态
     */
    public void updateOnOrder(Order order) {
        String playbookId = order.getAttr(Order.ODRATTR_PLAYBOOK_ID);
        PlaybookImpl playbook = allPlaybooks.get(playbookId);
        if ( playbook==null ) {
            return;
        }
        if ( order.getStateTuple().getState().isDone() ) {
            pendingOrders.remove(order);
        }
        PlaybookStateTuple newStateTuple = playbook.updateStateOnOrder(order);
        if ( newStateTuple!=null ) {
            playbookChangeStateTuple(playbook, newStateTuple,"Order "+order.getRef()+" "+order.getExchangeable()+" D:"+order.getDirection()+" P:"+PriceUtil.long2str(order.getLimitPrice())+" V:"+order.getVolume(OdrVolume.ReqVolume)+" F:"+order.getOffsetFlags()+" at "+DateUtil.date2str(mtService.getMarketTime()));
        }
    }

    /**
     * 判断超时Playbook
     */
    public void onNoopSecond() {
        for(PlaybookImpl playbook:activePlaybooks) {
            PlaybookStateTuple newStateTuple = playbook.updateStateOnNoop();
            if ( newStateTuple!=null ) {
                playbookChangeStateTuple(playbook, newStateTuple, "noop");
            }
        }
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("allOrderCount", allOrders.size());
        json.addProperty("pendingOrderCount", pendingOrders.size());
        json.addProperty("allPlaybookCount", allPlaybooks.size());
        json.add("activePlaybooks", JsonUtil.object2json(activePlaybooks));
        return json;
    }

    private void playbookChangeStateTuple(PlaybookImpl playbook, PlaybookStateTuple newStateTuple, String time) {
        if ( newStateTuple!=null ) {
            int lastOrderCount = playbook.getOrders().size();
            logger.info("Tradlet group "+group.getId()+" playbook "+playbook.getId()+" state is changed to "+newStateTuple.getState()+" on "+time);
            List<Order> playbookOrders = playbook.getOrders();
            //检查是否有新的报单
            if ( lastOrderCount!=playbookOrders.size() ) {
                Order newOrder = playbookOrders.get(lastOrderCount);
                addOrder(newOrder);
            }
            //检查Playbook状态
            if ( newStateTuple.getState().isDone() ) {
                activePlaybooks.remove(playbook);
            }
            group.onPlaybookStateChanged(playbook, newStateTuple);
        }
    }

    private void addOrder(Order order) {
        allOrders.add(order);
        pendingOrders.add(order);
    }

}
