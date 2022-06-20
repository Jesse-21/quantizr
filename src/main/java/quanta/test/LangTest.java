package quanta.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quanta.config.ServiceBase;
import quanta.util.XString;

@Component("LangTest")
public class LangTest extends ServiceBase implements TestIntf {
	private static final Logger log = LoggerFactory.getLogger(UtilsTest.class);

	@Override
	public void test() throws Exception {
		// both true
		log.debug("Contains Asian: " + XString.containsChinese("xxx已下架xxx"));
		log.debug("Contains Russian: " + XString.containsRussian("xxкиилxxx"));

		// both false
		log.debug("Contains Asian: " + XString.containsChinese("xxкиилxxx"));
		log.debug("Contains Russian: " + XString.containsRussian("xxx已下架xxx"));
	}
}