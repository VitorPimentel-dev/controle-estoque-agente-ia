package br.com.realtec.controleestoque.repository;

import br.com.realtec.controleestoque.entity.Pedido;
import br.com.realtec.controleestoque.entity.StatusPedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PedidoRepository extends JpaRepository<Pedido, String> {

    List<Pedido> findAllByOrderByDataCriacaoDesc();

    List<Pedido> findByStatusOrderByDataCriacaoDesc(StatusPedido status);
}