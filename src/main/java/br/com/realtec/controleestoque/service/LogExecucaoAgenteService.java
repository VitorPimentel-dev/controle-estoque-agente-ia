package br.com.realtec.controleestoque.service;

import br.com.realtec.controleestoque.entity.LogExecucaoAgente;
import br.com.realtec.controleestoque.repository.LogExecucaoAgenteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LogExecucaoAgenteService {

    private final LogExecucaoAgenteRepository logRepository;

    public LogExecucaoAgenteService(LogExecucaoAgenteRepository logRepository) {
        this.logRepository = logRepository;
    }

    public List<LogExecucaoAgente> listarHistorico() {
        return logRepository.findAllByOrderByDataHoraDesc();
    }

    @Transactional
    public LogExecucaoAgente registrarExecucao(
            String comandoRecebido,
            String acoesRealizadas,
            String resultadoEsperado,
            String resultadoObservado,
            String conclusao,
            boolean sucesso) {

        LogExecucaoAgente log = new LogExecucaoAgente();
        log.setComandoRecebido(comandoRecebido);
        log.setAcoesRealizadas(acoesRealizadas);
        log.setResultadoEsperado(resultadoEsperado);
        log.setResultadoObservado(resultadoObservado);
        log.setConclusao(conclusao);
        log.setSucesso(sucesso);

        return logRepository.save(log);
    }

    public List<LogExecucaoAgente> listarTodos() {
        return logRepository.findAllByOrderByDataHoraDesc();
    }
}