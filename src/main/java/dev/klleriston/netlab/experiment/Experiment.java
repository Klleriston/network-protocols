package dev.klleriston.netlab.experiment;

/** Um experimento executável pelo {@link ExperimentRunner}. */
public interface Experiment {

    String name();

    /** Executa o roteiro e retorna o resumo publicado em {@code ExperimentFinished}. */
    String run(ExperimentContext ctx) throws Exception;
}
