# language: pt
Funcionalidade: Saga da ordem de serviço
  Como oficina que opera com serviços independentes de atendimento, cobrança e execução
  Quero que a ordem de serviço avance de forma coordenada entre eles
  Para que nenhuma etapa fique inconsistente quando algo falha no caminho

  Contexto:
    Dado o serviço "Troca de pastilhas" no catálogo por "180.00" reais
    E a peça "Pastilha de freio" no estoque com 10 unidades a "120.00" reais

  Cenário: Fluxo completo, da abertura à conclusão do reparo
    Quando a recepção abre uma ordem de serviço para o veículo "ABC1D23"
    Então o diagnóstico é solicitado ao serviço de Execução
    E a ordem de serviço fica "RECEIVED"

    Quando a Execução conclui o diagnóstico com 1 "Troca de pastilhas" e 2 "Pastilha de freio"
    Então um orçamento de "420.00" reais é solicitado ao serviço de Billing

    Quando o Billing envia o orçamento ao cliente
    Então a ordem de serviço fica "PENDING_APPROVAL"

    Quando o cliente aprova o orçamento
    Então o estoque da peça "Pastilha de freio" é 8
    E o pagamento é solicitado ao serviço de Billing
    E a ordem de serviço fica "PENDING_PAYMENT"

    Quando o Billing confirma o pagamento
    Então o reparo é solicitado ao serviço de Execução
    E a ordem de serviço fica "IN_PROGRESS"

    Quando a Execução conclui o reparo
    Então a ordem de serviço fica "COMPLETED"
    E a saga termina como "COMPLETED"

  Cenário: Cliente recusa o orçamento
    Dado uma ordem de serviço aguardando a aprovação do orçamento
    Quando o cliente recusa o orçamento
    Então a ordem de serviço fica "CANCELED"
    E a Execução é avisada para cancelar o trabalho
    E o estoque da peça "Pastilha de freio" é 10
    E a saga termina como "CANCELED"

  Cenário: Pagamento recusado desfaz a reserva de estoque
    Dado uma ordem de serviço aguardando o pagamento
    E o estoque da peça "Pastilha de freio" é 8
    Quando o Billing informa que o pagamento falhou
    Então o estoque da peça "Pastilha de freio" é 10
    E o Billing é avisado para cancelar o orçamento
    E a Execução é avisada para cancelar o trabalho
    E a ordem de serviço fica "CANCELED"
    E a saga termina como "COMPENSATED"

  Cenário: Falha no reparo estorna o pagamento e devolve o estoque
    Dado uma ordem de serviço em execução
    Quando a Execução informa que o reparo falhou
    Então o estorno do pagamento é solicitado ao serviço de Billing
    E o estoque da peça "Pastilha de freio" é 10
    E a ordem de serviço fica "CANCELED"
    E a saga fica "COMPENSATING"
    Quando o Billing confirma o estorno
    Então a saga termina como "COMPENSATED"

  Cenário: Cliente não responde dentro do prazo
    Dado uma ordem de serviço aguardando a aprovação do orçamento
    Quando o prazo de aprovação expira
    Então o Billing é avisado para cancelar o orçamento
    E a ordem de serviço fica "CANCELED"
    E a saga termina como "COMPENSATED"

  Cenário: Uma resposta entregue duas vezes não é processada de novo
    Dado uma ordem de serviço aguardando a aprovação do orçamento
    Quando a aprovação do cliente é entregue duas vezes
    Então o estoque da peça "Pastilha de freio" é 8
    E o pagamento é solicitado ao serviço de Billing uma única vez
