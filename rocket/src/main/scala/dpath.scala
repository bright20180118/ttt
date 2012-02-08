package Top {

import Chisel._
import Node._;
import Constants._
import Instructions._

class ioDpathDmem extends Bundle()
{
  val req_addr  = UFix(VADDR_BITS+1, OUTPUT);
  val req_tag   = UFix(CPU_TAG_BITS, OUTPUT);
  val req_data  = Bits(64, OUTPUT);
  val resp_val  = Bool(INPUT);
  val resp_miss = Bool(INPUT);
  val resp_replay = Bool(INPUT);
  val resp_tag  = Bits(CPU_TAG_BITS, INPUT);
  val resp_data = Bits(64, INPUT);
  val resp_data_subword = Bits(64, INPUT);
}

class ioDpathImem extends Bundle()
{
  val req_addr  = UFix(VADDR_BITS+1, OUTPUT);
  val resp_data = Bits(32, INPUT);
}

class ioDpathAll extends Bundle()
{
  val host  = new ioHost();
  val ctrl  = new ioCtrlDpath().flip();
  val console = new ioConsole(List("valid","bits"));
  val debug = new ioDebug();
  val dmem  = new ioDpathDmem();
  val imem  = new ioDpathImem();
  val ptbr_wen = Bool(OUTPUT);
  val ptbr = UFix(PADDR_BITS, OUTPUT);
  val fpu = new ioDpathFPU();
}

class rocketDpath extends Component
{
  val io  = new ioDpathAll();
  
  val btb = new rocketDpathBTB(8); // # of entries in BTB
  
  val if_btb_target = btb.io.target;

  val pcr = new rocketDpathPCR(); 
  val ex_pcr = pcr.io.r.data;

  val alu = new rocketDpathALU(); 
  val ex_alu_out = alu.io.out; 
  val ex_alu_adder_out = alu.io.adder_out; 

  val div = new rocketDivider(64);
  val div_result = div.io.div_result_bits;
  val div_result_tag = div.io.div_result_tag;
  val div_result_val = div.io.div_result_val;
  
  val mul = new rocketMultiplier();
  val mul_result = mul.io.result; 
  val mul_result_tag = mul.io.result_tag; 
  val mul_result_val = mul.io.result_val;
  
  val rfile = new rocketDpathRegfile();

  // instruction fetch definitions
  val if_reg_pc         = Reg(resetVal = UFix(START_ADDR,VADDR_BITS+1));

  // instruction decode definitions
  val id_reg_valid     = Reg(resetVal = Bool(false));
  val id_reg_inst      = Reg(resetVal = NOP);
  val id_reg_pc        = Reg() { UFix(width = VADDR_BITS+1) };

  // execute definitions
  val ex_reg_valid          = Reg(resetVal = Bool(false));
  val ex_reg_pc             = Reg() { UFix() };
  val ex_reg_raddr2         = Reg() { UFix() };
  val ex_reg_op2            = Reg() { Bits() };
  val ex_reg_rs2            = Reg() { Bits() };
  val ex_reg_rs1            = Reg() { Bits() };
  val ex_reg_waddr          = Reg() { UFix() };
  val ex_reg_ctrl_eret      = Reg(resetVal = Bool(false));
  val ex_reg_ctrl_fn_dw     = Reg() { UFix() };
  val ex_reg_ctrl_fn_alu    = Reg() { UFix() };
  val ex_reg_ctrl_mul_val   = Reg(resetVal = Bool(false));
  val ex_reg_ctrl_mul_fn    = Reg() { UFix() };
  val ex_reg_ctrl_div_val   = Reg(resetVal = Bool(false));
  val ex_reg_ctrl_div_fn    = Reg() { UFix() };
  val ex_reg_ctrl_sel_wb    = Reg() { UFix() };
  val ex_reg_ctrl_ren_pcr   = Reg(resetVal = Bool(false));
  val ex_reg_ctrl_wen_pcr   = Reg(resetVal = Bool(false));
 	val ex_wdata						  = Wire() { Bits() }; 	

  // memory definitions
  val mem_reg_valid          = Reg(resetVal = Bool(false));
  val mem_reg_pc             = Reg() { UFix() };
  val mem_reg_waddr          = Reg() { UFix() };
  val mem_reg_wdata          = Reg() { Bits() };
  val mem_reg_raddr2         = Reg() { UFix() };
  val mem_reg_ctrl_mul_val   = Reg(resetVal = Bool(false));
  val mem_reg_ctrl_div_val   = Reg(resetVal = Bool(false));
  val mem_reg_ctrl_wen_pcr   = Reg(resetVal = Bool(false));
  val mem_wdata              = Wire() { Bits() }; 	
  
  // writeback definitions
  val wb_reg_valid          = Reg(resetVal = Bool(false));
  val wb_reg_pc             = Reg() { UFix() };
  val wb_reg_waddr          = Reg() { UFix() };
  val wb_reg_wdata          = Reg() { Bits() };
  val wb_reg_raddr2         = Reg() { UFix() };
  val wb_reg_ctrl_wen_pcr   = Reg(resetVal = Bool(false));
  val wb_reg_ll_wb          = Reg(resetVal = Bool(false));
  val wb_wdata              = Wire() { Bits() }; 	

  val dmem_resp_replay      = Wire() { Bool() }
  val r_dmem_resp_replay    = Reg(resetVal = Bool(false));
  val r_dmem_fp_replay      = Reg(resetVal = Bool(false));
  val r_dmem_resp_waddr     = Reg() { UFix() };
  
  // instruction fetch stage
  val if_pc_plus4 = if_reg_pc + UFix(4);

  val ex_pc_plus4 = ex_reg_pc + UFix(4);
  val ex_branch_target = ex_reg_pc + Cat(ex_reg_op2(VADDR_BITS-1,0), Bits(0,1)).toUFix

  val ex_ea_sign = Mux(ex_alu_adder_out(VADDR_BITS-1), ~ex_alu_adder_out(63,VADDR_BITS) === UFix(0), ex_alu_adder_out(63,VADDR_BITS) != UFix(0))
  val ex_effective_address = Cat(ex_ea_sign, ex_alu_adder_out(VADDR_BITS-1,0)).toUFix
  
  val ex_br_target_sel = Reg(io.ctrl.sel_alu2 === A2_BTYPE || io.ctrl.sel_alu2 === A2_JTYPE)
  val ex_br_target = Mux(ex_br_target_sel, ex_branch_target, ex_effective_address)
  btb.io.correct_target := ex_br_target

  val if_next_pc =
    Mux(io.ctrl.sel_pc === PC_BTB,  Cat(if_btb_target(VADDR_BITS-1), if_btb_target),
    Mux(io.ctrl.sel_pc === PC_EX4,  ex_pc_plus4,
    Mux(io.ctrl.sel_pc === PC_BR,   ex_br_target,
    Mux(io.ctrl.sel_pc === PC_PCR,  wb_reg_wdata(VADDR_BITS,0), // only used for ERET
    Mux(io.ctrl.sel_pc === PC_EVEC, Cat(pcr.io.evec(VADDR_BITS-1), pcr.io.evec),
    Mux(io.ctrl.sel_pc === PC_WB,   wb_reg_pc,
        if_pc_plus4)))))); // PC_4
        
  when (!io.ctrl.stallf) {
    if_reg_pc <== if_next_pc.toUFix;
  }
  
  io.ctrl.xcpt_ma_inst := if_next_pc(1,0) != Bits(0)

  io.imem.req_addr :=
    Mux(io.ctrl.stallf, if_reg_pc,
        if_next_pc.toUFix);

  btb.io.current_pc     := if_reg_pc;
  btb.io.hit            <> io.ctrl.btb_hit;
  btb.io.wen            <> io.ctrl.wen_btb;
  btb.io.clr            <> io.ctrl.clr_btb;
  btb.io.correct_pc     := ex_reg_pc;
  io.ctrl.btb_match     := id_reg_pc === ex_br_target;

  // instruction decode stage
  when (!io.ctrl.stalld) {
    id_reg_pc <== if_reg_pc;
    when(io.ctrl.killf) {
      id_reg_inst  <== NOP;
      id_reg_valid <== Bool(false);
    }
    otherwise {
      id_reg_inst  <== io.imem.resp_data;
      id_reg_valid <== Bool(true);
    }
  }

  val id_raddr1 = id_reg_inst(26,22).toUFix;
  val id_raddr2 = id_reg_inst(21,17).toUFix;

	// regfile read
  rfile.io.r0.en   <> io.ctrl.ren2;
  rfile.io.r0.addr := id_raddr2;
  val id_rdata2 = rfile.io.r0.data;

  rfile.io.r1.en   <> io.ctrl.ren1;
  rfile.io.r1.addr := id_raddr1;
  val id_rdata1 = rfile.io.r1.data;

  // destination register selection
  val id_waddr =
    Mux(io.ctrl.sel_wa === WA_RD, id_reg_inst(31,27).toUFix,
        RA); // WA_RA

  // bypass muxes
  val id_rs1 =
    Mux(io.ctrl.ex_wen && id_raddr1 === ex_reg_waddr,  ex_wdata,
    Mux(io.ctrl.mem_wen && id_raddr1 === mem_reg_waddr, mem_wdata,
    Mux((io.ctrl.wb_wen || wb_reg_ll_wb) && id_raddr1 === wb_reg_waddr, wb_wdata,
        id_rdata1)));

  val id_rs2 =
    Mux(io.ctrl.ex_wen && id_raddr2 === ex_reg_waddr,  ex_wdata,
    Mux(io.ctrl.mem_wen && id_raddr2 === mem_reg_waddr, mem_wdata,
    Mux((io.ctrl.wb_wen || wb_reg_ll_wb) && id_raddr2 === wb_reg_waddr, wb_wdata,
        id_rdata2)));

  // immediate generation
  val id_imm_bj = io.ctrl.sel_alu2 === A2_BTYPE || io.ctrl.sel_alu2 === A2_JTYPE
  val id_imm_l = io.ctrl.sel_alu2 === A2_LTYPE
  val id_imm_zero = io.ctrl.sel_alu2 === A2_ZERO || io.ctrl.sel_alu2 === A2_RTYPE
  val id_imm_ibz = io.ctrl.sel_alu2 === A2_ITYPE || io.ctrl.sel_alu2 === A2_BTYPE || id_imm_zero
  val id_imm_sign = Mux(id_imm_bj, id_reg_inst(31),
                    Mux(id_imm_l, id_reg_inst(26),
                    Mux(id_imm_zero, Bits(0,1),
                        id_reg_inst(21)))) // IMM_ITYPE
  val id_imm_small = Mux(id_imm_zero, Bits(0,12),
                         Cat(Mux(id_imm_bj, id_reg_inst(31,27), id_reg_inst(21,17)), id_reg_inst(16,10)))
  val id_imm = Cat(Fill(32, id_imm_sign),
                   Mux(id_imm_l, Cat(id_reg_inst(26,7), Bits(0,12)),
                   Mux(id_imm_ibz, Cat(Fill(20, id_imm_sign), id_imm_small),
                       Cat(Fill(7, id_imm_sign), id_reg_inst(31,7))))) // A2_JTYPE

  val id_op2 = Mux(io.ctrl.sel_alu2 === A2_RTYPE, id_rs2, id_imm)

  io.ctrl.inst := id_reg_inst;

  // execute stage
  ex_reg_pc             <== id_reg_pc;
  ex_reg_raddr2         <== id_raddr2;
  ex_reg_op2            <== id_op2;
  ex_reg_rs2            <== id_rs2;
  ex_reg_rs1            <== id_rs1;
  ex_reg_waddr          <== id_waddr;
  ex_reg_ctrl_fn_dw     <== io.ctrl.fn_dw.toUFix;
  ex_reg_ctrl_fn_alu    <== io.ctrl.fn_alu;
  ex_reg_ctrl_mul_fn    <== io.ctrl.mul_fn;
  ex_reg_ctrl_div_fn    <== io.ctrl.div_fn;
  ex_reg_ctrl_sel_wb    <== io.ctrl.sel_wb;
  ex_reg_ctrl_ren_pcr   <== io.ctrl.ren_pcr;

  when(io.ctrl.killd) {
    ex_reg_valid          <== Bool(false);
    ex_reg_ctrl_div_val 	<== Bool(false);
    ex_reg_ctrl_mul_val   <== Bool(false);
    ex_reg_ctrl_wen_pcr 	<== Bool(false);
    ex_reg_ctrl_eret			<== Bool(false);
  } 
  otherwise {
    ex_reg_valid          <== id_reg_valid;
    ex_reg_ctrl_div_val 	<== io.ctrl.div_val;
  	ex_reg_ctrl_mul_val   <== io.ctrl.mul_val;
    ex_reg_ctrl_wen_pcr		<== io.ctrl.wen_pcr;
    ex_reg_ctrl_eret			<== io.ctrl.id_eret;
  }

  alu.io.dw    := ex_reg_ctrl_fn_dw;
  alu.io.fn    := ex_reg_ctrl_fn_alu;
  alu.io.in2   := ex_reg_op2.toUFix;
  alu.io.in1   := ex_reg_rs1.toUFix;
  
  // divider
  div.io.dw        := ex_reg_ctrl_fn_dw;
  div.io.div_fn    := ex_reg_ctrl_div_fn;
  div.io.div_val   := ex_reg_ctrl_div_val;
  div.io.div_kill  := mem_reg_ctrl_div_val && io.ctrl.killm;
  div.io.div_waddr := ex_reg_waddr;
  div.io.dpath_rs1 := ex_reg_rs1;
  div.io.dpath_rs2 := ex_reg_rs2;
  div.io.div_result_rdy := !dmem_resp_replay
  
  io.ctrl.div_rdy 				:= div.io.div_rdy;
  io.ctrl.div_result_val := div.io.div_result_val;
  
  // multiplier
  mul.io.mul_val := ex_reg_ctrl_mul_val;
  mul.io.mul_kill:= mem_reg_ctrl_mul_val && io.ctrl.killm;
  mul.io.dw      := ex_reg_ctrl_fn_dw;
  mul.io.mul_fn	 := ex_reg_ctrl_mul_fn;
  mul.io.mul_tag := ex_reg_waddr;
  mul.io.in0		 := ex_reg_rs1;
  mul.io.in1		 := ex_reg_rs2;
 
  io.ctrl.mul_rdy        := mul.io.mul_rdy
  io.ctrl.mul_result_val := mul.io.result_val;
  mul.io.result_rdy      := !dmem_resp_replay && !div.io.div_result_val
  
  io.ctrl.ex_waddr := ex_reg_waddr; // for load/use hazard detection & bypass control

  // D$ request interface (registered inside D$ module)
  // other signals (req_val, req_rdy) connect to control module  
  io.dmem.req_addr  := ex_effective_address.toUFix;
  if (HAVE_FPU) {
    io.dmem.req_data := Mux(io.ctrl.ex_fp_val, io.fpu.store_data, ex_reg_rs2)
    io.dmem.req_tag := Cat(ex_reg_waddr, io.ctrl.ex_fp_val).toUFix
  }
  else {
    io.dmem.req_data := ex_reg_rs2
    io.dmem.req_tag := Cat(ex_reg_waddr, Bool(false)).toUFix
  }

	// processor control regfile read
  pcr.io.r.en   := ex_reg_ctrl_ren_pcr | ex_reg_ctrl_eret;
  pcr.io.r.addr := 
  	Mux(ex_reg_ctrl_eret, PCR_EPC, 
  		ex_reg_raddr2);

  pcr.io.host.from_wen <> io.host.from_wen;
  pcr.io.host.from     <> io.host.from;
  pcr.io.host.to       <> io.host.to;

  io.ctrl.irq_timer    := pcr.io.irq_timer;
  io.ctrl.irq_ipi      := pcr.io.irq_ipi;  
  io.ctrl.status       := pcr.io.status;
  io.ptbr              := pcr.io.ptbr;
  io.ptbr_wen          := pcr.io.ptbr_wen;
 	io.debug.error_mode  := pcr.io.debug.error_mode;
  
	// branch resolution logic
  io.ctrl.br_eq   := (ex_reg_rs1 === ex_reg_rs2);
  io.ctrl.br_ltu  := (ex_reg_rs1.toUFix < ex_reg_rs2.toUFix);
  io.ctrl.br_lt :=
    (~(ex_reg_rs1(63) ^ ex_reg_rs2(63)) & io.ctrl.br_ltu |
    ex_reg_rs1(63) & ~ex_reg_rs2(63)).toBool;

  // time stamp counter
  val tsc_reg = Reg(resetVal = UFix(0,64));
  tsc_reg <== tsc_reg + UFix(1);
  // instructions retired counter
  val irt_reg = Reg(resetVal = UFix(0,64));
  when (wb_reg_valid) { irt_reg <== irt_reg + UFix(1); }
  
	// writeback select mux
  ex_wdata :=
    Mux(ex_reg_ctrl_sel_wb === WB_PC,  Cat(Fill(64-VADDR_BITS, ex_pc_plus4(VADDR_BITS-1)), ex_pc_plus4),
    Mux(ex_reg_ctrl_sel_wb === WB_PCR, ex_pcr,
    Mux(ex_reg_ctrl_sel_wb === WB_TSC, tsc_reg,
    Mux(ex_reg_ctrl_sel_wb === WB_IRT, irt_reg,
        ex_alu_out)))).toBits; // WB_ALU
        
  // memory stage
  mem_reg_pc                <== ex_reg_pc;
  mem_reg_waddr             <== ex_reg_waddr;
  mem_reg_wdata             <== ex_wdata;
  mem_reg_raddr2            <== ex_reg_raddr2;
  mem_reg_ctrl_mul_val      <== ex_reg_ctrl_mul_val;
  mem_reg_ctrl_div_val      <== ex_reg_ctrl_div_val;
  
  when (io.ctrl.killx) {
    mem_reg_valid          <== Bool(false);
    mem_reg_ctrl_wen_pcr 	 <== Bool(false);
  }
  otherwise {
    mem_reg_valid          <== ex_reg_valid;
    mem_reg_ctrl_wen_pcr 	 <== ex_reg_ctrl_wen_pcr;
  }
  
  // for load/use hazard detection (load byte/halfword)
  io.ctrl.mem_waddr := mem_reg_waddr;
  io.ctrl.mem_valid := mem_reg_valid;

  mem_wdata := Mux(io.ctrl.mem_load, io.dmem.resp_data, mem_reg_wdata)

  // 32/64 bit load handling (moved to earlier in file)
      
  // writeback stage
  val dmem_resp_fpu = if (HAVE_FPU) io.dmem.resp_tag(0).toBool else Bool(false)
  val dmem_resp_waddr = io.dmem.resp_tag.toUFix >> UFix(1)
  dmem_resp_replay := io.dmem.resp_replay && !dmem_resp_fpu;
  r_dmem_resp_replay  <== dmem_resp_replay
  r_dmem_resp_waddr   <== dmem_resp_waddr
  r_dmem_fp_replay    <== io.dmem.resp_replay && dmem_resp_fpu;

  val mem_ll_waddr = Mux(dmem_resp_replay, dmem_resp_waddr,
                     Mux(div_result_val, div_result_tag,
                     Mux(mul_result_val, mul_result_tag,
                         mem_reg_waddr)))
  val mem_ll_wdata = Mux(div_result_val, div_result,
                     Mux(mul_result_val, mul_result,
                         mem_reg_wdata))
  val mem_ll_wb = mem_ll_waddr != UFix(0) &&
    (dmem_resp_replay || div_result_val || mul_result_val)

  wb_reg_pc             <== mem_reg_pc;
  wb_reg_ll_wb          <== mem_ll_wb
  wb_reg_waddr          <== mem_ll_waddr
  wb_reg_wdata          <== mem_ll_wdata
  wb_reg_raddr2         <== mem_reg_raddr2;

  when (io.ctrl.killm) {
    wb_reg_valid          <== Bool(false);
    wb_reg_ctrl_wen_pcr 	<== Bool(false);
  }
  otherwise {
    wb_reg_valid          <== mem_reg_valid;
    wb_reg_ctrl_wen_pcr 	<== mem_reg_ctrl_wen_pcr;
  }

	// regfile write
  val wb_src_dmem = Reg(io.ctrl.mem_load) && wb_reg_valid || r_dmem_resp_replay
  wb_wdata := Mux(wb_src_dmem, io.dmem.resp_data_subword, wb_reg_wdata)
  rfile.io.w0.addr := wb_reg_waddr
  rfile.io.w0.en   := io.ctrl.wb_wen || wb_reg_ll_wb
  rfile.io.w0.data := wb_wdata
  
  io.ctrl.wb_waddr := wb_reg_waddr;
  io.ctrl.mem_wb := dmem_resp_replay;
  
  // scoreboard clear (for div/mul and D$ load miss writebacks)
  io.ctrl.sboard_clr   := mem_ll_wb
  io.ctrl.sboard_clra  := mem_ll_waddr
  io.ctrl.fp_sboard_clr  := r_dmem_fp_replay
  io.ctrl.fp_sboard_clra := r_dmem_resp_waddr

	// processor control regfile write
  pcr.io.w.addr := wb_reg_raddr2;
  pcr.io.w.en   := wb_reg_ctrl_wen_pcr;
  pcr.io.w.data := wb_reg_wdata;

  pcr.io.di           := io.ctrl.irq_disable;
  pcr.io.ei           := io.ctrl.irq_enable;
  pcr.io.eret      	  := io.ctrl.wb_eret;
  pcr.io.exception 	  := io.ctrl.exception;
  pcr.io.cause 			  := io.ctrl.cause;
  pcr.io.pc					  := wb_reg_pc;
  pcr.io.badvaddr_wen := io.ctrl.badvaddr_wen;
  io.console.bits := pcr.io.console_data;
  io.console.valid := pcr.io.console_val;
}

}
