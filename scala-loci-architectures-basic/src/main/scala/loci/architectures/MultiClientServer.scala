package loci
package architectures

@multitier
object MultiClientServer {
  abstract class ServerPeer[C <: ClientPeer[_]: PeerTypeTag] extends Peer {
    type Tie <: Multiple[C]
    implicit def connectDefault = Default.Listen[C]
  }

  abstract class ClientPeer[S <: ServerPeer[_]: PeerTypeTag] extends Peer {
    type Tie <: Single[S]
    implicit def connectDefault = Default.Request[S]
  }
}
