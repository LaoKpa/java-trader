package trader.tool;

import java.io.File;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import trader.TraderMain;
import trader.common.beans.BeansContainer;
import trader.common.exchangeable.Exchange;
import trader.common.exchangeable.ExchangeableTradingTimes;
import trader.common.util.ConversionUtil;
import trader.common.util.DateUtil;
import trader.common.util.FileUtil;
import trader.common.util.IniFile;
import trader.common.util.StringUtil.KVPair;
import trader.common.util.TraderHomeUtil;
import trader.service.util.CmdAction;

/**
 * 启动交易服务
 */
public class ServiceStartAction implements CmdAction {

    private File statusFile;

    @Override
    public String getCommand() {
        return "service.start";
    }

    @Override
    public void usage(PrintWriter writer) {
        writer.println("service start");
        writer.println("\t启动交易服务");
    }

    @Override
    public int execute(BeansContainer beansContainer, PrintWriter writer, List<KVPair> options) throws Exception {
        //解析参数
        init(options);
        ExchangeableTradingTimes tradingTimes = Exchange.SHFE.detectTradingTimes("au", LocalDateTime.now());
        if ( tradingTimes==null ) {
            writer.println(DateUtil.date2str(LocalDateTime.now())+" Not trading time");
            return 1;
        }
        LocalDate tradingDay = tradingTimes.getTradingDay();
        long traderPid = getTraderPid();
        if ( traderPid>0 ) {
            writer.println(DateUtil.date2str(LocalDateTime.now())+" Trader process is running: "+traderPid);
            return 1;
        }
        writer.println(DateUtil.date2str(LocalDateTime.now())+" Starting trader from config "+System.getProperty(TraderHomeUtil.PROP_TRADER_CONFIG_FILE)+", home: " + TraderHomeUtil.getTraderHome()+", trading day: "+tradingDay);
        saveStatusStart();
        ConfigurableApplicationContext context = SpringApplication.run(TraderMain.class, options.toArray(new String[options.size()]));
        saveStatusReady();
        context.addApplicationListener(new ApplicationListener<ContextClosedEvent>() {
            public void onApplicationEvent(ContextClosedEvent event) {
                synchronized(statusFile) {
                    statusFile.notify();
                }
            }
        });
        synchronized(statusFile) {
            statusFile.wait();
        }
        return 0;
    }

    private void init(List<KVPair> options) {
        File workDir = TraderHomeUtil.getDirectory(TraderHomeUtil.DIR_WORK);
        workDir.mkdirs();
        statusFile = getStatusFile();
    }

    /**
     * 判断pid文件所记载的trader进程是否存在
     */
    private long getTraderPid() throws Exception {
        long result = 0;
        if ( statusFile.exists() && statusFile.length()>0 ) {
            IniFile iniFile = new IniFile(statusFile);
            IniFile.Section section = iniFile.getSection("start");
            long pid = ConversionUtil.toLong( section.get("pid") );
            Optional<ProcessHandle> process = ProcessHandle.of(pid);
            if ( process.isPresent() ) {
                result = pid;
            }
        }
        return result;
    }

    /**
     * 保存status文件 [starting] section
     */
    private void saveStatusStart() {
        String text = "[start]\n"
            +"pid="+ProcessHandle.current().pid()+"\n"
            +"startTime="+DateUtil.date2str(LocalDateTime.now())+"\n"
            +"traderHome="+TraderHomeUtil.getTraderHome().getAbsolutePath()+"\n"
            +"traderCfgFile="+System.getProperty(TraderHomeUtil.PROP_TRADER_CONFIG_FILE)+"\n";
        try{
            FileUtil.save(statusFile, text);
        }catch(Throwable t) {}
    }

    /**
     * 保存status文件 [ready] section
     */
    private void saveStatusReady() {
        String text = "[ready]\n"
                +"readyTime="+DateUtil.date2str(LocalDateTime.now())+"\n";
        try{
            FileUtil.save(statusFile, text, true);
        }catch(Throwable t) {}
    }

    public static File getStatusFile() {
        String configFile = System.getProperty(TraderHomeUtil.PROP_TRADER_CONFIG_FILE);
        String traderCfgName = (new File(configFile)).getName();
        int lastDot = traderCfgName.lastIndexOf('.');
        if ( lastDot>0 ) {
            traderCfgName = traderCfgName.substring(0, lastDot)+".status";
        }
        File workDir = TraderHomeUtil.getDirectory(TraderHomeUtil.DIR_WORK);
        File result = new File(workDir, traderCfgName);
        return result;
    }

}
