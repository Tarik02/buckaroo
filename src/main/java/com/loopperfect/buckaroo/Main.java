package com.loopperfect.buckaroo;

import com.loopperfect.buckaroo.cli.CLICommand;
import com.loopperfect.buckaroo.cli.CLIParsers;
import com.loopperfect.buckaroo.tasks.LoggingTasks;
import com.loopperfect.buckaroo.virtualterminal.TerminalBuffer;
import com.loopperfect.buckaroo.virtualterminal.components.Component;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.annotations.NonNull;
import io.reactivex.observables.ConnectableObservable;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.Schedulers;
import org.fusesource.jansi.AnsiConsole;
import org.jparsec.Parser;
import org.jparsec.error.ParserException;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.loopperfect.buckaroo.views.ProgressView.progressView;
import static com.loopperfect.buckaroo.views.SummaryView.summaryView;

public final class Main {

    private Main() {}

    public static void main(final String[] args) {

        if (args.length == 0) {
            System.out.println("Buck, Buck, Buckaroo! \uD83E\uDD20");
            System.out.println("https://buckaroo.readthedocs.io/");
            return;
        }

        // We need to change the default behaviour of Schedulers.io()
        // so that it has a bounded thread-pool.
        // Take at least 2 threads to prevent dead-locks.
        final int threads = Math.max(2, Runtime.getRuntime().availableProcessors());

        RxJavaPlugins.setIoSchedulerHandler(scheduler ->
            Schedulers.from(Executors.newFixedThreadPool(threads)));

        final FileSystem fs = FileSystems.getDefault();

        final String rawCommand = String.join(" ", args);

        // Send the command to the logging server, if present
        LoggingTasks.log(fs, rawCommand).subscribe(); // Ignore any results

        // Parse the command
        final Parser<CLICommand> commandParser = CLIParsers.commandParser;

        try {
            final CLICommand command = commandParser.parse(rawCommand);

            final ExecutorService executorService = Executors.newCachedThreadPool();
            final Scheduler scheduler = Schedulers.from(executorService);

            final Context ctx = Context.of(fs, scheduler);
            final Observable<Event> task = command.routine().apply(ctx);

            final ConnectableObservable<Event> events$ = task
                .observeOn(scheduler)
                .subscribeOn(scheduler)
                .publish();

            final Observable<Component> current$ = progressView(events$)
                .subscribeOn(Schedulers.computation())
                .sample(100, TimeUnit.MILLISECONDS)
                .distinctUntilChanged();

            final Observable<Component> summary$ = summaryView(events$)
                .subscribeOn(Schedulers.computation())
                .lastElement().toObservable();

            AnsiConsole.systemInstall();
            TerminalBuffer buffer = new TerminalBuffer();
            Observable
                .merge(current$, summary$)
                .map(c -> c.render(100))
                .doOnNext(buffer::flip)
                .doOnError(error -> {
                    error.printStackTrace();
                    executorService.shutdown();
                    scheduler.shutdown();
                }).doOnComplete(() -> {
                    executorService.shutdown();
                    scheduler.shutdown();
                }).subscribe();

            events$.connect();
        } catch (final ParserException e) {
            System.out.println("Uh oh!");
            System.out.println(e.getMessage());
        }
    }
}
