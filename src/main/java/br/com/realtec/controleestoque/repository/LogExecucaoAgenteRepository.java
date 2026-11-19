package br.com.realtec.controleestoque.repository;

import br.com.realtec.controleestoque.entity.LogExecucaoAgente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogExecucaoAgenteRepository extends JpaRepository<LogExecucaoAgente, Long> {

    List<LogExecucaoAgente> findAllByOrderByDataHoraDesc();
}