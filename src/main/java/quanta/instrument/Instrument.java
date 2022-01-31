package quanta.instrument;

import static quanta.util.Util.ok;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quanta.config.SessionContext;
import quanta.model.client.PrincipalName;
import quanta.util.ThreadLocals;

/*
 * Instrumentation for the app (for Performance Monitoring)
 * 
 * Any method can be annotated with @PerfMon to gather performance statistics but mainly we just do
 * this on our external-facing REST controller interface.
 * 
 * Eventually we can perhaps use OpenTelemetry+SigNoz for performance metrics, but for now to be
 * able to gather specific targeted metrics in the simplest way possible, we just use this class
 * internally in the app rather than more complex approaches, since we were able to implement this
 * letting AOP do all the work, and since perfMonAdvice() method below is so trivial.
 * 
 * todo-1: Does instrumentation ONLY work on PUBLIC methods?? I think I noticed that happening but
 * didn't prove it yet or look into it yet. If this is true it might be that only public methods are
 * proxied by spring which would make sense, but I don't kow if that's the case.
 */
@Aspect
@Component
public class Instrument {
	private static final Logger log = LoggerFactory.getLogger(Instrument.class);

	public static final int CAPTURE_THRESHOLD = 10; // 10 for prod

	private static final int MAX_EVENTS = 10000;
	public static List<PerfMonEvent> data = Collections.synchronizedList(new LinkedList());

	// @Around("execution(@PerfMon * *(..))") <--- this one also did work, but I changed to the simpler
	// looking version.
	@Around("@annotation(PerfMon)")
	public Object perfMonAdvice(ProceedingJoinPoint jp) throws Throwable {
		if (data.size() > MAX_EVENTS) {
			data.clear();
		}

		Object value = null;
		long startTime = System.currentTimeMillis();
		String userName = null;
		try {
			SessionContext sc = ThreadLocals.getSC();
			if (ok(sc) && !PrincipalName.ANON.s().equals(sc.getUserName())) {
				userName = sc.getUserName();
			}

			value = jp.proceed();
		} catch (Throwable e) {
			throw e;
		} finally {
			int duration = (int) (System.currentTimeMillis() - startTime);
			MethodSignature signature = (MethodSignature) jp.getSignature();

			///////////////////
			// DO NOT DELETE:
			// The following are untested examples, in case we ever need more info...
			// log.debug("full method description: " + signature.getMethod());
			// log.debug("method name: " + signature.getMethod().getName());
			// log.debug("declaring type: " + signature.getDeclaringType());

			// // Method args
			// log.debug("Method args names:");
			// Arrays.stream(signature.getParameterNames()).forEach(s -> log.debug("arg name: " + s));

			// log.debug("Method args types:");
			// Arrays.stream(signature.getParameterTypes()).forEach(s -> log.debug("arg type: " + s));

			// log.debug("Method args values:");
			// if (ok(jp.getArgs())) {
			// Arrays.stream(jp.getArgs()).forEach(o -> log.debug("arg value: " + o.toString()));
			// }

			// // Additional Information
			// log.debug("returning type: " + signature.getReturnType());
			// log.debug("method modifier: " + Modifier.toString(signature.getModifiers()));
			// Arrays.stream(signature.getExceptionTypes()).forEach(aClass -> log.debug("exception type: " +
			// aClass));

			Method method = signature.getMethod();
			PerfMon annotation = method.getAnnotation(PerfMon.class);

			if (duration > CAPTURE_THRESHOLD) {
				PerfMonEvent event = new PerfMonEvent();
				event.duration = duration;
				event.event = annotation.category().equals("") ? signature.getName() : //
						(annotation.category() + "." + signature.getName());
				event.user = userName;
				data.add(event);
			}
		}
		return value;
	}
}